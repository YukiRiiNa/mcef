/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.MCEFSettings;
import com.cinemamod.mcef.internal.MCEFDownloadListener;
import net.minecraft.client.resources.ClientPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;

/**
 * <p>
 * mcef.libraries.path is where MCEF will store any required binaries. By default,
 * /path/to/.minecraft/mods/mcef-libraries.
 * <p>
 * jcef.path is the location of the standard java-cef bundle. By default,
 * /path/to/mcef-libraries/<normalized platform name> where normalized platform name comes from
 * {@link MCEFPlatform#getNormalizedName()}. This is what java-cef uses internally to find the
 * installation. Also see {@link org.cef.CefApp}.
 */
@Mixin(ClientPackSource.class)
public class CefDownloadMixin {
    @Unique
    private static void setupLibraryPath() throws IOException {
        final File mcefLibrariesDir;

        // Check for development environment
        // TODO: handle eclipse/others
        // i.e. mcef-repo/neoforge/build
        File buildDir = new File("../build");
        if (buildDir.exists() && buildDir.isDirectory()) {
            mcefLibrariesDir = new File(buildDir, "mcef-libraries/");
        } else {
            mcefLibrariesDir = new File("mods/mcef-libraries/");
        }

        mcefLibrariesDir.mkdirs();

        System.setProperty("mcef.libraries.path", mcefLibrariesDir.getCanonicalPath());
        System.setProperty("jcef.path", new File(mcefLibrariesDir, MCEFPlatform.getPlatform().getNormalizedName()).getCanonicalPath());
    }

    @Inject(at = @At("HEAD"), method = "<clinit>")
    private static void sinit(CallbackInfo callbackInfo) {
        try {
            setupLibraryPath();
        } catch (IOException e) {
            e.printStackTrace();
        }

        Thread downloadThread = new Thread(() -> {
            String javaCefCommit;

            try {
                javaCefCommit = MCEF.getJavaCefCommit();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            MCEF.getLogger().info("java-cef commit: " + javaCefCommit);

            MCEFSettings settings = MCEF.getSettings();
            MCEFDownloader downloader = new MCEFDownloader(settings.getDownloadMirror(), javaCefCommit, MCEFPlatform.getPlatform());

            boolean downloadJcefBuild = false;
            File mcefLibrariesDir = new File(System.getProperty("mcef.libraries.path"));

            // 修复：优先判断配置文件中的 skip-download 选项
            if (settings.isSkipDownload()) {
                MCEF.getLogger().info("Skipping JCEF download and network checks as 'skip-download' is set to true in config.");
                // 依然需要检查本地文件是否存在，如果不存在给个警告提示
                if (!mcefLibrariesDir.exists()) {
                    MCEF.getLogger().warn("MCEF skip-download is true, but local libraries are missing! This might cause a crash.");
                }
            } else {
                // 未开启跳过下载时，才进行网络请求获取 checksum
                try {
                    downloadJcefBuild = !downloader.downloadJavaCefChecksum();
                } catch (IOException e) {
                    MCEF.getLogger().error("Failed to download JCEF checksum.", e);
                    MCEFDownloadListener.INSTANCE.setFailed(true);
                    return;
                }

                downloadJcefBuild |= !mcefLibrariesDir.exists();

                // 如果需要下载，则进行完整的下载与解压流程
                if (downloadJcefBuild) {
                    try {
                        downloader.downloadJavaCefBuild();
                    } catch (IOException e) {
                        MCEF.getLogger().error("Failed to download JCEF.", e);
                        MCEFDownloadListener.INSTANCE.setFailed(true);
                        return;
                    }

                    downloader.extractJavaCefBuild(true);
                }
            }

            MCEFDownloadListener.INSTANCE.setDone(true);
        });
        downloadThread.start();
    }
}
