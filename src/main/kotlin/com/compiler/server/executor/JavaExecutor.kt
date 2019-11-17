package com.compiler.server.executor

import com.compiler.server.model.ExceptionDescriptor
import com.compiler.server.model.JavaExecutionResult
import com.compiler.server.utils.escapeString
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

const val MAX_OUTPUT_SIZE = 100 * 1024
const val EXECUTION_TIMEOUT = 10000L

@Component
class JavaExecutor {

  data class ProgramOutput(
    val standardOutput: String,
    val errorOutput: String,
    val exception: Exception? = null
  ) {
    fun asExecutionResult(): JavaExecutionResult {
      return JavaExecutionResult(
        text = "<outStream>$standardOutput\n</outStream>",
        exception = exception?.let {
          ExceptionDescriptor(it.message ?: "no message", it::class.java.toString())
        })
    }
  }

  fun execute(args: List<String>): JavaExecutionResult {
    return Runtime.getRuntime().exec(args.toTypedArray()).use {
      outputStream.close()
      val interruptCondition = ProcessInterruptCondition()
      val standardOut = InputStreamReader(this.inputStream).buffered()
      val standardError = InputStreamReader(this.errorStream).buffered()
      val errorText = StringBuilder()
      val standardText = StringBuilder()
      val standardThread = appendTo(standardText, standardOut, interruptCondition)
      standardThread.start()
      val errorThread = appendTo(errorText, standardError, interruptCondition)
      errorThread.start()

      val interruptMsg = Executors.newSingleThreadExecutor().submit(
        interruptAfter(
          process = this,
          threads = listOf(standardThread, errorThread),
          interruptCondition = interruptCondition
        )
      )
      val interruptType: ConditionType
      try {
        waitFor()
        interruptCondition.exitNow()
        interruptType = interruptMsg.get()
        standardThread.join(10000)
        errorThread.join(10000)
      }
      finally {
        try {
          standardOut.close()
          standardError.close()
        }
        catch (e: IOException) {
          e.printStackTrace()
        }
      }
      val exception = if (errorText.toString().isNotEmpty()) {
        Exception(errorText.toString())
      }
      else null
      when (interruptType) {
        ConditionType.NORMAL -> ProgramOutput(
          standardText.toString(),
          errorText.toString(),
          exception
        ).asExecutionResult()
        ConditionType.LOG_SIZE -> ProgramOutput(
          ExecutorMessages.TOO_LONG_OUTPUT_MESSAGE,
          errorText.toString(),
          exception
        ).asExecutionResult()
        ConditionType.TIMEOUT -> ProgramOutput(
          ExecutorMessages.TIMEOUT_MESSAGE,
          errorText.toString(),
          exception
        ).asExecutionResult()
      }
    }
  }

  private fun <T> Process.use(body: Process.() -> T) = try {
    body()
  }
  finally {
    destroy()
  }

  private fun interruptAfter(
    process: Process,
    threads: List<Thread>,
    interruptCondition: ProcessInterruptCondition
  ): Callable<ConditionType> = Callable {
    val result = interruptCondition.waitForCondition(EXECUTION_TIMEOUT)
    threads.forEach { it.interrupt() }
    process.destroy()
    result
  }

  private fun appendTo(
    string: StringBuilder,
    from: BufferedReader,
    interruptCondition: ProcessInterruptCondition
  ) = Thread {
    try {
      while (true) {
        val line = from.readLine()
        if (Thread.interrupted() || line == null) break
        interruptCondition.appendCharacterCounter(line.length)
        string.appendln(escapeString(line))
      }
    }
    catch (e: Throwable) {
      if (!Thread.interrupted()) {
        e.printStackTrace()
      }
    }
  }
}

class ProcessInterruptCondition {
  private lateinit var conditionBreak: ConditionType
  private var totalCharactersOutput: Int = 0
  private val lock = ReentrantLock()
  private val condition = lock.newCondition()

  fun waitForCondition(delay: Long): ConditionType = withLock {
    if (!condition.await(delay, TimeUnit.MILLISECONDS))
      return@withLock ConditionType.TIMEOUT
    return@withLock conditionBreak
  }

  fun appendCharacterCounter(length: Int) = withLock {
    this.totalCharactersOutput += length
    if (totalCharactersOutput > MAX_OUTPUT_SIZE) {
      this.conditionBreak = ConditionType.LOG_SIZE
      condition.signal()
    }
  }

  fun exitNow() = withLock {
    this.conditionBreak = ConditionType.NORMAL
    condition.signal()
  }

  private fun <T> withLock(block: () -> T): T {
    lock.lock()
    try {
      return block()
    }
    finally {
      lock.unlock()
    }
  }
}

enum class ConditionType {
  TIMEOUT,
  LOG_SIZE,
  NORMAL
}

class JavaArgumentsBuilder(
  val classPaths: String,
  val mainClass: String,
  val policy: Path,
  val memoryLimit: Int,
  val args: String
) {
  fun toArguments(): List<String> {
    return listOf(
      "java",
      "-Djava.security.manager",
      "-Xmx" + memoryLimit + "M",
      "-Djava.security.policy=$policy",
      "-classpath"
    ) + classPaths + mainClass + args.split(" ")
  }
}