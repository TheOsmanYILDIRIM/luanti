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

final class ModZipImporter {
	private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;

	private ModZipImporter() {}

	@NonNull
	static String install(@NonNull Context context, @NonNull Uri source) throws IOException {
		File cacheZip = new File(Utils.getCacheDirectory(context), "mod-import.zip");
		copyUriToFile(context, source, cacheZip);

		try (ZipFile zip = new ZipFile(cacheZip)) {
			String rootPrefix = findModRoot(zip);
			String modName = findModName(zip, rootPrefix);
			if (modName == null || modName.isEmpty())
				modName = fallbackModName(context, source, rootPrefix);

			if (!modName.matches("[A-Za-z0-9_]+"))
				throw new IOException("Invalid mod name: " + modName);

			File modsDir = Utils.createDirs(Utils.getUserDataDirectory(context), "mods");
			File staging = new File(modsDir, ".import-" + modName + "-" + System.nanoTime());
			File target = new File(modsDir, modName);

			if (!staging.mkdirs())
				throw new IOException("Could not create temporary mod directory");

			boolean success = false;
			try {
				extract(zip, rootPrefix, staging);

				if (target.exists())
					deleteRecursively(target);
				if (!staging.renameTo(target))
					throw new IOException("Could not move imported mod into place");

				success = true;
				return modName;
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

	private static String findModRoot(ZipFile zip) throws IOException {
		boolean rootLooksLikeMod = false;
		Set<String> topLevelDirectories = new HashSet<>();

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = normalizeEntryName(entry.getName());
			if (name.isEmpty() || name.startsWith("__MACOSX/"))
				continue;

			if (name.equals("init.lua") || name.equals("mod.conf"))
				rootLooksLikeMod = true;

			int slash = name.indexOf('/');
			if (slash > 0)
				topLevelDirectories.add(name.substring(0, slash));
			else if (!entry.isDirectory())
				topLevelDirectories.add("");
		}

		if (rootLooksLikeMod)
			return "";

		if (topLevelDirectories.size() == 1) {
			String only = topLevelDirectories.iterator().next();
			if (!only.isEmpty() &&
					(zip.getEntry(only + "/init.lua") != null || zip.getEntry(only + "/mod.conf") != null))
				return only + "/";
		}

		throw new IOException("ZIP does not contain a single Luanti mod");
	}

	private static String findModName(ZipFile zip, String rootPrefix) throws IOException {
		ZipEntry conf = zip.getEntry(rootPrefix + "mod.conf");
		if (conf == null)
			return null;

		try (InputStream in = zip.getInputStream(conf)) {
			String text = readSmallText(in, 64 * 1024);
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

	private static String fallbackModName(Context context, Uri source, String rootPrefix) {
		if (!rootPrefix.isEmpty())
			return sanitizeName(rootPrefix.substring(0, rootPrefix.length() - 1));

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
			displayName = "imported_mod";
		String lower = displayName.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".zip"))
			displayName = displayName.substring(0, displayName.length() - 4);
		return sanitizeName(displayName);
	}

	private static String sanitizeName(String name) {
		String sanitized = name.replaceAll("[^A-Za-z0-9_]", "_");
		return sanitized.isEmpty() ? "imported_mod" : sanitized;
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
						throw new IOException("Mod ZIP is too large");
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

	private static String readSmallText(InputStream in, int maxBytes) throws IOException {
		byte[] buffer = new byte[4096];
		StringBuilder result = new StringBuilder();
		int total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > maxBytes)
				throw new IOException("mod.conf is unexpectedly large");
			result.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
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
			throw new IOException("Could not replace existing mod: " + file.getName());
	}
}
