import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Args(val host: String, val port: Int, val numConnections: Int, val contentLength: Int, val duration: Int)

fun main(argv: Array<String>) {
    val args = Args(
        if (argv.size >= 1) argv[0] else "localhost",
        if (argv.size >= 2) Integer.parseInt(argv[1]) else 9000,
        if (argv.size >= 3) Integer.parseInt(argv[2]) else 10,
        if (argv.size >= 4) Integer.parseInt(argv[3]) else 32,
        if (argv.size >= 5) Integer.parseInt(argv[4]) else 5_000
    )
    println(args)
    val destination = InetSocketAddress(args.host, args.port);
    val content = ByteArray(args.contentLength)
    content.fill('z'.code.toByte())
    val connectionCount = AtomicInteger()
    val echoCount = AtomicInteger()
    val startTime = AtomicLong()
    val channel = Channel<Boolean>(args.numConnections)
    val selectorManager = ActorSelectorManager(Dispatchers.IO)
    runBlocking {
        repeat(args.numConnections) {
            launch {
                val socket = aSocket(selectorManager).tcp().connect(destination)
                val id = connectionCount.incrementAndGet()
                if (id == args.numConnections) { // connections created, signal via channel
                    println("barrier opened!")
                    startTime.set(System.currentTimeMillis())
                    repeat(args.numConnections) {
                        channel.send(true)
                    }
                }
                channel.receive() // wait for start signal
                val deadline = startTime.get() + args.duration
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                val buffer = ByteBuffer.wrap(content.copyOf(args.contentLength)) // seed buffer with copy of content
                while (System.currentTimeMillis() < deadline) {
                    output.writeFully(buffer)
                    buffer.flip()
                    input.readFully(buffer)
                    if (buffer.hasRemaining() || !Arrays.equals(content, buffer.array())) {
                        throw AssertionError()
                    }
                    echoCount.incrementAndGet()
                    buffer.flip()
                }
            }
        }
    }
    val duration = System.currentTimeMillis() - startTime.get()
    System.out.printf("duration: %d ms, throughput: %f msg/sec\n", duration, echoCount.get() / (duration / 1000.0))
}