//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.Agent;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import java.io.File;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.util.List;
import java.util.Map;
import net.minecraftforge.gradle.GradleStartCommon;

public class GradleStart extends GradleStartCommon {
    public static void main(String[] args) throws Throwable {
        hackNatives();
        (new GradleStart()).launch(args);
    }

    protected String getBounceClass() {
        return "net.minecraft.launchwrapper.Launch";
    }

    protected String getTweakClass() {
        return "";
    }

    protected void setDefaultArguments(Map<String, String> argMap) {
        argMap.put("version", "1.8.9");
        argMap.put("assetIndex", "1.8");
        argMap.put("assetsDir", "C:/Users/lmopa/.gradle/caches/minecraft/assets");
        argMap.put("accessToken", "FML");
        argMap.put("userProperties", "{}");
        argMap.put("username", null);
        argMap.put("password", null);

    }

    protected void preLaunch(Map<String, String> argMap, List<String> extras) {
        if (!Strings.isNullOrEmpty((String)argMap.get("password"))) {
            GradleStartCommon.LOGGER.info("Password found, attempting login");
            this.attemptLogin(argMap);
        }

        if (!Strings.isNullOrEmpty((String)argMap.get("assetIndex"))) {
        }

    }

    private static void hackNatives() {
        String paths = System.getProperty("java.library.path");
        String nativesDir = "C:/Users/lmopa/.gradle/caches/minecraft/net/minecraft/natives/1.8.9";
        if (Strings.isNullOrEmpty(paths)) {
            paths = nativesDir;
        } else {
            paths = paths + File.pathSeparator + nativesDir;
        }

        System.setProperty("java.library.path", paths);

        try {
            Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set((Object)null, (Object)null);
        } catch (Throwable var3) {
        }

    }

    private void attemptLogin(Map<String, String> argMap) {
        YggdrasilUserAuthentication auth = (YggdrasilUserAuthentication)(new YggdrasilAuthenticationService(Proxy.NO_PROXY, "1")).createUserAuthentication(Agent.MINECRAFT);
        auth.setUsername((String)argMap.get("username"));
        auth.setPassword((String)argMap.get("password"));
        argMap.put("password", null);

        try {
            auth.logIn();
        } catch (AuthenticationException e) {
            LOGGER.error("-- Login failed!  " + e.getMessage());
            Throwables.propagate(e);
            return;
        }

        LOGGER.info("Login Succesful!");
        argMap.put("accessToken", auth.getAuthenticatedToken());
        argMap.put("uuid", auth.getSelectedProfile().getId().toString().replace("-", ""));
        argMap.put("username", auth.getSelectedProfile().getName());
        argMap.put("userType", auth.getUserType().getName());
        argMap.put("userProperties", (new GsonBuilder()).registerTypeAdapter(PropertyMap.class, new PropertyMap.Serializer()).create().toJson(auth.getUserProperties()));
    }
}
