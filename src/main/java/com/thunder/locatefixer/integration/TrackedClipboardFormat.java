package com.thunder.locatefixer.integration;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class TrackedClipboardFormat {

    public static final Map<Clipboard, String> CLIPBOARD_FILE_MAP = new HashMap<>();

    public static Clipboard loadAndTrack(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ClipboardFormat format = ClipboardFormat.findByFile(file);
            if (format != null) {
                ClipboardReader reader = format.getReader(in);
                Clipboard clipboard = reader.read();
                CLIPBOARD_FILE_MAP.put(clipboard, file.getName());
                return clipboard;
            }
        } catch (Exception e) {
            System.err.println("[LocateFixer] Failed to track schematic load: " + e.getMessage());
        }
        return null;
    }

    public static String getFileName(Clipboard clipboard) {
        return CLIPBOARD_FILE_MAP.getOrDefault(clipboard, null);
    }
}