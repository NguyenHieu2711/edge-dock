package com.nguyenhieu.edgedock

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class ShizukuShellService : IShizukuShellService.Stub() {

    override fun execCommand(command: String?): String {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) return ""
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd).apply {
                redirectErrorStream(true)
            }
            val proc = pb.start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    out.append(line).append('\n')
                    line = reader.readLine()
                }
            }
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            out.toString().trim()
        } catch (e: Throwable) {
            "ERR:${e.message}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
