package com.way.facebiometricfix

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MainHook : XposedModule() {

    private val promptGuards = mutableSetOf<PromptIconGuard>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var retiring = false
    private var systemUiProcess = false

    private fun logInfo(msg: String) {
        log(Log.INFO, TAG, msg)
    }

    private fun logError(msg: String, tr: Throwable) {
        log(Log.ERROR, TAG, msg, tr)
    }

    private val preAuthContextHooker = XposedInterface.Hooker { chain ->
        val packageName = runCatching { chain.getArg(4) as? String }.getOrNull()
        if (packageName != null && packageName != SYSTEM_UI_PACKAGE) {
            APP_PRE_AUTH_PACKAGE.set(packageName)
        }
        try {
            chain.proceed()
        } finally {
            APP_PRE_AUTH_PACKAGE.remove()
        }
    }

    private val faceStrengthCheckHooker = XposedInterface.Hooker { chain ->
        val packageName = APP_PRE_AUTH_PACKAGE.get()
        val sensorStrength = runCatching { chain.getArg(0) as? Int }.getOrNull() ?: return@Hooker false
        val requestedStrength = runCatching { chain.getArg(1) as? Int }.getOrNull() ?: return@Hooker false
        if (packageName != null &&
            packageName != CODEBOOK_PACKAGE &&
            sensorStrength == OEM_STRENGTH_MASK &&
            (requestedStrength == STRONG_AUTHENTICATORS ||
                requestedStrength == WEAK_AUTHENTICATORS)
        ) {
            return@Hooker true
        }

        // Keep the exact ColorOS implementation for all other paths,
        // especially SystemUI and provider/lockout operations.
        val maskedStrength = sensorStrength and 32767
        if (((requestedStrength.inv()) and maskedStrength) != 0) return@Hooker false
        var candidate = 1
        while (candidate <= requestedStrength) {
            if (candidate == maskedStrength) return@Hooker true
            candidate = (candidate shl 1) or 1
        }
        false
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        logInfo("hooking biometrics in system_server ...")
        installHooks(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == SYSTEM_UI_PACKAGE) {
            systemUiProcess = true
            hookSystemUi(param.classLoader)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        APP_PRE_AUTH_PACKAGE.remove()
        retiring = true
        if (!systemUiProcess) return true
        // libxposed requires external callbacks to be removed before returning true.
        val cleanup = FutureTask { promptGuards.toList().forEach { it.dispose() } }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else mainHandler.post(cleanup)
        try {
            cleanup.get(5, TimeUnit.SECONDS)
        } catch (error: Exception) {
            cleanup.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            retiring = false
            logError("FAIL clearing prompt listeners before hot reload", error)
            return false
        }
        logInfo("Hot reloading FaceBiometricFix hooks ...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        if (!param.isSystemServer) {
            systemUiProcess = true
            reloadSystemUi(param)
            return
        }
        var replacedPreAuth = false
        var replacedStrength = false
        var targetClassLoader: ClassLoader? = null

        for (oldHandle in param.oldHookHandles) {
            if (targetClassLoader == null) {
                targetClassLoader = oldHandle.executable.declaringClass.classLoader
            }
            when (oldHandle.id) {
                HOOK_ID_PRE_AUTH_CONTEXT -> {
                    runCatching {
                        oldHandle.replaceHook(preAuthContextHooker)
                        replacedPreAuth = true
                        logInfo("OK: hot-replaced PreAuthInfo app context hook")
                    }.onFailure {
                        logError("FAIL hot-replace PreAuthInfo hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }
                HOOK_ID_FACE_STRENGTH_CHECK -> {
                    runCatching {
                        oldHandle.replaceHook(faceStrengthCheckHooker)
                        replacedStrength = true
                        logInfo("OK: hot-replaced app-only 4095 strength check")
                    }.onFailure {
                        logError("FAIL hot-replace strength hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }
                else -> {
                    runCatching { oldHandle.unhook() }
                        .onFailure { logError("FAIL unhook old handle ${oldHandle.id}", it) }
                }
            }
        }

        if (!replacedPreAuth || !replacedStrength) {
            val classLoader = targetClassLoader ?: Thread.currentThread().contextClassLoader
            if (classLoader != null) {
                if (!replacedPreAuth) hookAppPreAuthContext(classLoader)
                if (!replacedStrength) hookAppFaceStrengthCheck(classLoader)
            } else {
                log(Log.WARN, TAG, "Hot reload fallback skipped: system_server ClassLoader unavailable")
            }
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        hookAppPreAuthContext(classLoader)
        hookAppFaceStrengthCheck(classLoader)
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
        runCatching {
            val controller = Class.forName(
                "com.android.systemui.biometrics.AuthController", false, classLoader
            )
            val method = controller.getDeclaredMethod(
                "onBiometricAuthenticated", Int::class.javaPrimitiveType
            )
            hook(method)
                .setId(HOOK_ID_PROMPT_ICON)
                .intercept(promptIconHooker(controller))
            logInfo("OK: prompt-local face confirmation icon fix")
        }.onFailure { logError("FAIL SystemUI prompt icon hook", it) }
    }

    private fun promptIconHooker(controller: Class<*>): XposedInterface.Hooker {
        // Resolve the known dialog field once, without inspecting unrelated fields.
        val dialogField = (generateSequence(controller) { it.superclass }
            .mapNotNull { type ->
                runCatching { type.getDeclaredField("mCurrentDialog") }.getOrNull()
            }.firstOrNull() ?: error("AuthController#mCurrentDialog not found"))
            .apply { isAccessible = true }
        return XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            // AuthController dispatches BiometricPrompt callbacks on the main thread.
            // It does not handle keyguard authentication.
            if (!retiring && chain.getArg(0) == FACE_MODALITY &&
                Looper.myLooper() == Looper.getMainLooper()
            ) {
                runCatching {
                    val root = dialogField.get(chain.thisObject) as? View
                    if (root != null && promptGuards.none { it.root === root }) {
                        PromptIconGuard.attach(
                            root,
                            onDisposed = { promptGuards.remove(it) },
                            onError = { logError("FAIL updating prompt icons", it) }
                        )?.let { promptGuards.add(it) }
                    }
                }.onFailure { logError("FAIL attaching prompt icon guard", it) }
            }
            result
        }
    }

    private fun reloadSystemUi(param: XposedModuleInterface.HotReloadedParam) {
        var replaced = false
        var classLoader: ClassLoader? = null
        for (handle in param.oldHookHandles) {
            if (handle.id == HOOK_ID_PROMPT_ICON && !replaced) {
                classLoader = handle.executable.declaringClass.classLoader
                runCatching {
                    handle.replaceHook(promptIconHooker(handle.executable.declaringClass))
                    replaced = true
                }.onFailure {
                    logError("FAIL hot-replacing prompt icon hook", it)
                    runCatching { handle.unhook() }
                }
            } else {
                runCatching { handle.unhook() }
                    .onFailure { logError("FAIL unhook old handle ${handle.id}", it) }
            }
        }
        if (!replaced) {
            (classLoader ?: Thread.currentThread().contextClassLoader)?.let { hookSystemUi(it) }
        }
    }

    /**
     * 在 PreAuthInfo 计算普通应用资格期间记录 opPackageName。
     *
     * isAtLeastStrength() 在 system_server 内部执行时无法可靠取得原始调用者 UID，
     * 所以必须从 PreAuthInfo 的参数传递调用上下文。
     */
    private fun hookAppPreAuthContext(classLoader: ClassLoader) {
        runCatching {
            val preAuthInfoClass = Class.forName(
                "com.android.server.biometrics.PreAuthInfo",
                false,
                classLoader
            )
            val method = preAuthInfoClass.getDeclaredMethod(
                "getStatusForBiometricAuthenticator",
                Class.forName("android.app.admin.DevicePolicyManager", false, classLoader),
                Class.forName("com.android.server.biometrics.BiometricService\$SettingObserver", false, classLoader),
                Class.forName("com.android.server.biometrics.BiometricSensor", false, classLoader),
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                Class.forName("com.android.server.biometrics.BiometricCameraManager", false, classLoader)
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_PRE_AUTH_CONTEXT)
                .intercept(preAuthContextHooker)
            logInfo("OK: PreAuthInfo app context hook")
        }.onFailure { logError("FAIL PreAuthInfo context hook", it) }
    }

    /** 仅在普通 App 的预检查调用链中放行 4095 人脸强度。 */
    private fun hookAppFaceStrengthCheck(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName(
                "com.android.server.biometrics.Utils",
                false,
                classLoader
            )
            val method = utilsClass.getDeclaredMethod(
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_FACE_STRENGTH_CHECK)
                .intercept(faceStrengthCheckHooker)
            logInfo("OK: app-only 4095 strength check")
        }.onFailure { logError("FAIL app strength hook", it) }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"

        private const val HOOK_ID_PRE_AUTH_CONTEXT = "hook_pre_auth_context"
        private const val HOOK_ID_FACE_STRENGTH_CHECK = "hook_face_strength_check"
        private const val HOOK_ID_PROMPT_ICON = "hook_prompt_icon"

        /** 厂商私有强度掩码（人脸传感器上报值） */
        private const val OEM_STRENGTH_MASK = 4095
        private const val STRONG_AUTHENTICATORS = 15
        private const val WEAK_AUTHENTICATORS = 255
        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

        private const val STATUS_OK = 1
        private const val STATUS_INSUFFICIENT_STRENGTH = 4
        private const val FACE_MODALITY = 8
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"
    }
}
