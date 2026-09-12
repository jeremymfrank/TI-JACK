#include <jni.h>
#include <exception>
#include <string>
#include "tivars_lib_cpp.hpp"

namespace {

std::string fromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_tijack_evo_LegacyTiConverter_convertToEvoNative(
    JNIEnv* env,
    jobject,
    jstring inputPath,
    jstring outputPath,
    jboolean smart
) {
    try {
        const std::string input = fromJString(env, inputPath);
        const std::string output = fromJString(env, outputPath);
        if (input.empty() || output.empty()) {
            throw std::runtime_error("converter received an empty file path");
        }

        auto variable = tivars::TIVarFile::loadFromFile(input);
        if (variable.isCorrupt()) {
            throw std::runtime_error("legacy TI file checksum or structure is invalid");
        }
        if (variable.hasMultipleEntries()) {
            throw std::runtime_error("grouped or multi-entry TI files are not supported yet");
        }

        variable.convertToModel("84Evo", smart == JNI_TRUE);
        if (!variable.isEvoFormat()) {
            throw std::runtime_error("conversion did not produce a TI-84 Evo variable");
        }

        const std::string written = variable.saveVarToFile(output);
        return env->NewStringUTF(written.c_str());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "unknown legacy TI conversion error");
        return nullptr;
    }
}
