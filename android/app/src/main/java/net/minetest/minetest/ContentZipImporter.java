/*
Luanti
Copyright (C) 2026 Luanti contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation; either version 2.1 of the License, or
(at your option) any later version.
*/

package net.minetest.minetest;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ContentZipImporter {
	private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;

	enum PackageType {
		MOD("mod", "mods"),
		GAME("game", "games"),
		TEXTURE_PACK("texture pack", "textures");

		final String label;
		final String directory;

		PackageType(String label, String directory) {
			this.label = label;
			this.directory = directory;
		}
	}

	static final class InstallResult {
		final PackageType type;
		final String name;

		InstallResult(PackageType type, String name) {
			this.type = type;
			this.name = name;
		}
	}

	private static final class PackageInfo {
		final PackageType type;
		final String rootPrefix;

		PackageInfo(PackageType type, String rootPrefix) {
			this.type = type;
			this.rootPrefix = rootPrefix;
		}
	}

	private ContentZipImporter() {}

	@NonNull
	static InstallResult install(@NonNull Context context, @NonNull Uri source) throws IOException {
		File cacheZip = new File(Utils.getCacheDirectory(context), "content-import.zip");
		copyUriToFile(context, source, cacheZip);

		try (ZipFile zip = new ZipFile(cacheZip)) {
			PackageInfo pkg = findPackageRoot(zip);
			String packageName;

			if (pkg.type == PackageType.MOD) {
				packageName = findModName(zip, pkg.rootPrefix);
				if (packageName == null || packageName.isEmpty())
					packageName = fallbackPackageName(
						context, source, pkg.rootPrefix, "imported_mod");
			} else if (pkg.type == PackageType.GAME) {
				packageName = fallbackPackageName(
					context, source, pkg.rootPrefix, "imported_game");
			} else {
				packageName = fallbackPackageName(
					context, source, pkg.rootPrefix, "imported_texture_pack");
			}

			if (!packageName.matches("[A-Za-z0-9_]+"))
				throw new IOException("Invalid " + pkg.type.label + " name: " + packageName);

			File contentDir = Utils.createDirs(
				Utils.getUserDataDirectory(context), pkg.type.directory);
			File staging = new File(contentDir,
				".import-" + packageName + "-" + System.nanoTime());
			File target = new File(contentDir, packageName);

			if (!staging.mkdirs())
				throw new IOException("Could not create temporary " + pkg.type.label + " directory");

			boolean success = false;
			try {
				extract(zip, pkg.rootPrefix, staging);

				if (target.exists())
					deleteRecursively(target);
				if (!staging.renameTo(target))
					throw new IOException("Could not move imported " + pkg.type.label + " into place");

				success = true;
				return new InstallResult(pkg.type, packageName);
			} finally {
				if (!success)
					deleteRecursively(staging);
			}
		} finally {
			//noinspection ResultOfMethodCallIgnored
			cacheZip.delete();
		}
	}

	private static void copyUriToFile(Context context, Uri source, File destination) throws IOException {
		try (InputStream in = context.getContentResolver().openInputStream(source)) {
			if (in == null)
				throw new IOException("Could not open selected ZIP");

			try (OutputStream out = new FileOutputStream(destination)) {
				byte[] buffer = new byte[16384];
				int read;
				while ((read = in.read(buffer)) != -1)
					out.write(buffer, 0, read);
			}
		}
	}

	private static PackageInfo findPackageRoot(ZipFile zip) throws IOException {
		boolean rootLooksLikeGame = false;
		boolean rootLooksLikeMod = false;
		boolean rootLooksLikeTexturePack = false;
		Set<String> topLevelDirectories = new HashSet<>();

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = normalizeEntryName(entry.getName());
			if (name.isEmpty() || name.startsWith("__MACOSX/"))
				continue;

			if (name.equals("game.conf"))
				rootLooksLikeGame = true;
			if (name.equals("texture_pack.conf"))
				rootLooksLikeTexturePack = true;
			if (name.equals("init.lua") || name.equals("mod.conf"))
				rootLooksLikeMod = true;

			int slash = name.indexOf('/');
			if (slash > 0)
				topLevelDirectories.add(name.substring(0, slash));
			else if (!entry.isDirectory())
				topLevelDirectories.add("");
		}

		if (rootLooksLikeGame)
			return new PackageInfo(PackageType.GAME, "");
		if (rootLooksLikeTexturePack)
			return new PackageInfo(PackageType.TEXTURE_PACK, "");
		if (rootLooksLikeMod)
			return new PackageInfo(PackageType.MOD, "");

		if (topLevelDirectories.size() == 1) {
			String only = topLevelDirectories.iterator().next();
			if (!only.isEmpty()) {
				String prefix = only + "/";
				if (zip.getEntry(prefix + "game.conf") != null)
					return new PackageInfo(PackageType.GAME, prefix);
				if (zip.getEntry(prefix + "texture_pack.conf") != null)
					return new PackageInfo(PackageType.TEXTURE_PACK, prefix);
				if (zip.getEntry(prefix + "init.lua") != null ||
						zip.getEntry(prefix + "mod.conf") != null)
					return new PackageInfo(PackageType.MOD, prefix);
			}
		}

		throw new IOException("ZIP does not contain a single Luanti mod, game, or texture pack");
	}

	private static String findModName(ZipFile zip, String rootPrefix) throws IOException {
		ZipEntry conf = zip.getEntry(rootPrefix + "mod.conf");
		if (conf == null)
			return null;

		try (InputStream in = zip.getInputStream(conf)) {
			String text = readSmallText(in, 64 * 1024, "mod.conf");
			String[] lines = text.split("\\r?\\n");
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#"))
					continue;
				int equals = trimmed.indexOf('=');
				if (equals <= 0)
					continue;
				if (!trimmed.substring(0, equals).trim().equals("name"))
					continue;
				String value = trimmed.substring(equals + 1).trim();
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

	private static String fallbackPackageName(
			Context context, Uri source, String rootPrefix, String fallback) {
		if (!rootPrefix.isEmpty())
			return sanitizeName(rootPrefix.substring(0, rootPrefix.length() - 1), fallback);

		String displayName = null;
		try (Cursor cursor = context.getContentResolver().query(
				source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (index >= 0)
					displayName = cursor.getString(index);
			}
		}

		if (displayName == null || displayName.isEmpty())
			displayName = fallback;
		String lower = displayName.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".zip"))
			displayName = displayName.substring(0, displayName.length() - 4);
		return sanitizeName(displayName, fallback);
	}

	private static String sanitizeName(String name, String fallback) {
		String sanitized = name.replaceAll("[^A-Za-z0-9_]", "_");
		return sanitized.isEmpty() ? fallback : sanitized;
	}

	private static void extract(ZipFile zip, String rootPrefix, File destination) throws IOException {
		String destinationPath = destination.getCanonicalPath() + File.separator;
		long totalWritten = 0;

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = normalizeEntryName(entry.getName());
			if (name.isEmpty() || name.startsWith("__MACOSX/") || !name.startsWith(rootPrefix))
				continue;

			String relative = name.substring(rootPrefix.length());
			if (relative.isEmpty())
				continue;

			File outFile = new File(destination, relative);
			String outPath = outFile.getCanonicalPath();
			if (!outPath.startsWith(destinationPath))
				throw new IOException("Unsafe path in ZIP: " + entry.getName());

			if (entry.isDirectory()) {
				if (!outFile.isDirectory() && !outFile.mkdirs())
					throw new IOException("Could not create directory: " + relative);
				continue;
			}

			File parent = outFile.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs())
				throw new IOException("Could not create directory: " + parent.getName());

			try (InputStream in = zip.getInputStream(entry);
			     OutputStream out = new FileOutputStream(outFile)) {
				byte[] buffer = new byte[16384];
				int read;
				while ((read = in.read(buffer)) != -1) {
					totalWritten += read;
					if (totalWritten > MAX_UNCOMPRESSED_BYTES)
						throw new IOException("Content ZIP is too large");
					out.write(buffer, 0, read);
				}
			}
		}
	}

	private static String normalizeEntryName(String name) throws IOException {
		String normalized = name.replace('\\', '/');
		while (normalized.startsWith("./"))
			normalized = normalized.substring(2);
		if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals(".."))
			throw new IOException("Unsafe path in ZIP: " + name);
		return normalized;
	}

	private static String readSmallText(InputStream in, int maxBytes, String fileName)
			throws IOException {
		byte[] buffer = new byte[4096];
		StringBuilder result = new StringBuilder();
		int total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > maxBytes)
				throw new IOException(fileName + " is unexpectedly large");
			result.append(new String(buffer, 0, read,
				java.nio.charset.StandardCharsets.UTF_8));
		}
		return result.toString();
	}

	private static void deleteRecursively(File file) throws IOException {
		if (!file.exists())
			return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children)
					deleteRecursively(child);
			}
		}
		if (!file.delete())
			throw new IOException("Could not replace existing content: " + file.getName());
	}
}
