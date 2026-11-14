package com.zeroconvert.ime

import com.zeroconvert.ime.assist.AssistPolicyTransport
import org.json.JSONObject

/** Runs the production Rust decisions even when the JVM cannot load Android JNI. */
object RustAssistPolicy : AssistPolicyTransport {
    private val process by lazy {
        ProcessBuilder(checkNotNull(System.getProperty("zerocon.assistPolicyExecutable")))
            .redirectError(ProcessBuilder.Redirect.INHERIT).start().also { process ->
                Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
            }
    }
    private val writer by lazy { process.outputStream.bufferedWriter(Charsets.UTF_8) }
    private val reader by lazy { process.inputStream.bufferedReader(Charsets.UTF_8) }

    @Synchronized override fun request(request: JSONObject): JSONObject {
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val reply = JSONObject(checkNotNull(reader.readLine()) { "Rust policy process exited" })
        check(!reply.has("error")) { reply.optString("error") }
        return reply
    }
}
