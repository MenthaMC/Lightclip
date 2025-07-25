package dev.menthamc.lightclip.integrated.leavesclip.mixin.plugins.condition;

import dev.menthamc.lightclip.Lightclip;
import dev.menthamc.lightclip.integrated.leavesclip.logger.Logger;
import dev.menthamc.lightclip.integrated.leavesclip.logger.SimpleLogger;
import org.leavesmc.plugin.mixin.condition.BuildInfoProvider;
import org.leavesmc.plugin.mixin.condition.data.BuildInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BuildInfoInjector {
    private static final Logger logger = new SimpleLogger("BuildInfoInjector");

    public static void inject() {
        String buildInfoString;
        try (InputStream inputStream = Lightclip.class.getResourceAsStream("/META-INF/build-info")) {
            buildInfoString = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to read build info", e);
            throw new RuntimeException(e);
        }
        if (buildInfoString.endsWith("\tDEV")) {
            buildInfoString = buildInfoString.replaceFirst("\tDEV", "\t0");
        }
        BuildInfo buildInfo = BuildInfo.fromString(buildInfoString);
        logger.info(buildInfo.toString());
        BuildInfoProvider.INSTANCE.setBuildInfo(buildInfo);
    }
}
