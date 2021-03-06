/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** $Id$ */

package org.intellij.images.vfs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.reference.SoftReference;
import com.intellij.util.LogicalRoot;
import com.intellij.util.LogicalRootsManager;
import com.intellij.util.SVGLoader;
import com.intellij.util.ui.JBUI.ScaleContext;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.common.bytesource.ByteSourceArray;
import org.apache.commons.imaging.formats.ico.IcoImageParser;
import org.intellij.images.editor.ImageDocument;
import org.intellij.images.editor.ImageDocument.ScaledImageProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;

import static com.intellij.util.ui.JBUI.ScaleType.OBJ_SCALE;

/**
 * Image loader utility.
 *
 * @author <a href="mailto:aefimov.box@gmail.com">Alexey Efimov</a>
 */
public final class IfsUtil {
  private static final Logger LOG = Logger.getInstance("#org.intellij.images.vfs.IfsUtil");

  public static final String ICO_FORMAT = "ico";
  public static final String SVG_FORMAT = "svg";

  private static final Key<Long> TIMESTAMP_KEY = Key.create("Image.timeStamp");
  private static final Key<String> FORMAT_KEY = Key.create("Image.format");
  private static final Key<SoftReference<ScaledImageProvider>> IMAGE_PROVIDER_REF_KEY = Key.create("Image.bufferedImageProvider");
  private static final IcoImageParser ICO_IMAGE_PARSER = new IcoImageParser();

  /**
   * Load image data for file and put user data attributes into file.
   *
   * @param file File
   * @return true if file image is loaded.
   * @throws java.io.IOException if image can not be loaded
   */
  private static boolean refresh(@NotNull VirtualFile file) throws IOException {
    Long loadedTimeStamp = file.getUserData(TIMESTAMP_KEY);
    SoftReference<ScaledImageProvider> imageProviderRef = file.getUserData(IMAGE_PROVIDER_REF_KEY);
    if (loadedTimeStamp == null || loadedTimeStamp.longValue() != file.getTimeStamp() || SoftReference.dereference(imageProviderRef) == null) {
      try {
        final byte[] content = file.contentsToByteArray();
        file.putUserData(IMAGE_PROVIDER_REF_KEY, null);

        if (ICO_FORMAT.equalsIgnoreCase(file.getExtension())) {
          try {
            final BufferedImage image = ICO_IMAGE_PARSER.getBufferedImage(new ByteSourceArray(content), null);
            file.putUserData(FORMAT_KEY, ICO_FORMAT);
            file.putUserData(IMAGE_PROVIDER_REF_KEY, new SoftReference<>((scale, ancestor) -> image));
            return true;
          }
          catch (ImageReadException ignore) { }
        }

        if (isSVG(file)) {
          final Ref<URL> url = Ref.create();
          try {
            url.set(new File(file.getPath()).toURI().toURL());
          }
          catch (MalformedURLException ex) {
            LOG.warn(ex.getMessage());
          }

          try {
            // ensure svg can be displayed
            SVGLoader.load(url.get(), new ByteArrayInputStream(content), 1.0f);
          }
          catch (Throwable t) {
            LOG.warn(url.get() + " " + t.getMessage());
            return false;
          }

          file.putUserData(FORMAT_KEY, SVG_FORMAT);
          file.putUserData(IMAGE_PROVIDER_REF_KEY, new SoftReference<>(new ImageDocument.CachedScaledImageProvider() {
            final ScaleContext.Cache<BufferedImage> cache = new ScaleContext.Cache<>((ctx) -> {
              try {
                return SVGLoader.loadHiDPI(url.get(), new ByteArrayInputStream(content), ctx);
              }
              catch (Throwable t) {
                LOG.warn(url.get() + " " + t.getMessage());
                return null;
              }
            });
            @Override
            public void clearCache() {
              cache.clear();
            }
            @Override
            public BufferedImage apply(Double zoom, Component ancestor) {
              ScaleContext ctx = ScaleContext.create(ancestor);
              ctx.update(OBJ_SCALE.of(zoom));
              return cache.getOrProvide(ctx);
            }
          }));
          return true;
        }

        InputStream inputStream = new ByteArrayInputStream(content, 0, content.length);
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
          Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(imageInputStream);
          if (imageReaders.hasNext()) {
            ImageReader imageReader = imageReaders.next();
            try {
              file.putUserData(FORMAT_KEY, imageReader.getFormatName());
              ImageReadParam param = imageReader.getDefaultReadParam();
              imageReader.setInput(imageInputStream, true, true);
              int minIndex = imageReader.getMinIndex();
              BufferedImage image = imageReader.read(minIndex, param);
              file.putUserData(IMAGE_PROVIDER_REF_KEY, new SoftReference<>((zoom, ancestor) -> image));
              return true;
            }
            finally {
              imageReader.dispose();
            }
          }
        }
      } finally {
        // We perform loading no more needed
        file.putUserData(TIMESTAMP_KEY, file.getTimeStamp());
      }
    }
    return false;
  }

  @Nullable
  public static BufferedImage getImage(@NotNull VirtualFile file) throws IOException {
    return getImage(file, null);
  }

  @Nullable
  public static BufferedImage getImage(@NotNull VirtualFile file, @Nullable Component ancestor) throws IOException {
    ScaledImageProvider imageProvider = getImageProvider(file);
    if (imageProvider == null) return null;
    return imageProvider.apply(1d, ancestor);
  }

  @Nullable
  public static ScaledImageProvider getImageProvider(@NotNull VirtualFile file) throws IOException {
    refresh(file);
    SoftReference<ScaledImageProvider> imageProviderRef = file.getUserData(IMAGE_PROVIDER_REF_KEY);
    return SoftReference.dereference(imageProviderRef);
  }

  public static boolean isSVG(@Nullable VirtualFile file) {
    return file != null && SVG_FORMAT.equalsIgnoreCase(file.getExtension());
  }

  @Nullable
  public static String getFormat(@NotNull VirtualFile file) throws IOException {
    refresh(file);
    return file.getUserData(FORMAT_KEY);
  }

  public static String getReferencePath(Project project, VirtualFile file) {
    final LogicalRoot logicalRoot = LogicalRootsManager.getLogicalRootsManager(project).findLogicalRoot(file);
    if (logicalRoot != null) {
      return getRelativePath(file, logicalRoot.getVirtualFile());
    }

    ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
    if (sourceRoot != null) {
      return getRelativePath(file, sourceRoot);
    }

    VirtualFile root = fileIndex.getContentRootForFile(file);
    if (root != null) {
      return getRelativePath(file, root);
    }

    return file.getPath();
  }

  private static String getRelativePath(final VirtualFile file, final VirtualFile root) {
    if (root.equals(file)) {
      return file.getPath();
    }
    return "/" + VfsUtilCore.getRelativePath(file, root, '/');
  }
}
