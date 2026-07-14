package com.apex.maptool.unity;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Cache texture: BufferedImage + size (px). Tránh load lại mỗi repaint.
 */
public final class TextureCache {

    private final Map<Path, BufferedImage> images = new HashMap<>();
    private final Map<Path, int[]> sizes = new HashMap<>();

    /** Load full image (cache). null nếu fail. */
    public BufferedImage image(Path p) {
        if (p == null) return null;
        if (images.containsKey(p)) return images.get(p);
        BufferedImage img = null;
        try {
            if (Files.exists(p)) img = ImageIO.read(p.toFile());
        } catch (IOException e) {
            System.err.println("[TextureCache] read fail " + p + ": " + e.getMessage());
        }
        images.put(p, img);
        if (img != null) sizes.put(p, new int[]{img.getWidth(), img.getHeight()});
        return img;
    }

    /** Size px [w,h] đọc nhanh từ header (không decode full). Fallback [0,0]. */
    public int[] size(Path p) {
        if (p == null) return new int[]{0, 0};
        int[] cached = sizes.get(p);
        if (cached != null) return cached;
        int[] dim = readSizeFast(p);
        sizes.put(p, dim);
        return dim;
    }

    private static int[] readSizeFast(Path p) {
        if (p == null || !Files.exists(p)) return new int[]{0, 0};
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) return new int[]{0, 0};
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            System.err.println("[TextureCache] size fail " + p + ": " + e.getMessage());
        }
        return new int[]{0, 0};
    }
}
