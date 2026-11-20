plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.undcover.freedom.pyramid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // 脱糖:minSdk 24 下 java.time / java.util.stream / java.nio.file 等 JDK 库 API 改写为 j$ 实现
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 脱糖运行时库(实际打包在 :app,此处声明以启用本模块代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

chaquopy {
    defaultConfig {
        version = "3.10"
        // 优先使用项目随附的 Python 3.10 构建环境；Chaquopy 3.10 不能使用 Python 3.11+。
        // 若该环境不存在，则回退到 PATH 自动探测。
        val localBuildPython = "D:/Desktop/AIcode/TV/toolchains/python/python310/python.exe"
        if (file(localBuildPython).exists()) {
            buildPython(localBuildPython)
        }
        pip {
            options("-i", "https://mirrors.aliyun.com/pypi/simple/")
            options("--find-links", file("wheels/chaquopy-prebuilt").absolutePath)
            install("lxml")
            install("ujson")
            install("pyquery==2.0.2")
            install("requests")
            // jsonpath 0.54 只有 py2 源码包、无 wheel,本地移植后以 wheel 安装(见 wheels/README.md)
            install(file("wheels/jsonpath-0.54-py3-none-any.whl").absolutePath)
            install("cachetools")
            install("pycryptodome")
            install("beautifulsoup4")
        }
    }
    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/python"))
        }
    }
}
