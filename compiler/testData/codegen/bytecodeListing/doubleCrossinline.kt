// IGNORE_BACKEND: JVM_IR

// FULL_JDK
// COMMON_COROUTINES_TEST
// WITH_RUNTIME
// WITH_COROUTINES

import helpers.*
import COROUTINES_PACKAGE.*
import java.util.concurrent.ForkJoinPool

interface Flow<T> {
    suspend fun consumeEach(consumer: FlowConsumer<T>)
}

interface FlowConsumer<T> {
    suspend fun consume(value: T)
}

// This functions cross-inlines action into an implementation of FlowConsumer interface
suspend inline fun <T> Flow<T>.consumeEach(crossinline action: suspend (T) -> Unit) =
    consumeEach(object : FlowConsumer<T> {
        override suspend fun consume(value: T) = action(value)
    })

var log = ""

inline fun <T> Flow<T>.onEach(crossinline action: suspend (T) -> Unit): Flow<T> = object : Flow<T> {
    override suspend fun consumeEach(consumer: FlowConsumer<T>) {
        this@onEach.consumeEach { value ->
            log += value
            action(value)
            consumer.consume(value)
        }
    }
}

suspend fun suspendMe() = suspendCoroutine<Unit> {
    ForkJoinPool.commonPool().execute { it.resume(Unit) }
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun blackhole(value: Int) {}

fun box(): String {
    builder {
        val f1 = object : Flow<Int> {
            override suspend fun consumeEach(consumer: FlowConsumer<Int>) {
                for (x in 1..10) consumer.consume(x)
            }
        }
        val f2 = f1.onEach {
            suspendMe() // <------ suspending function here
        }
        f2.consumeEach { value ->
            blackhole(value)
        }
    }
    if (log != "12345678910") return log
    return "OK"
}
