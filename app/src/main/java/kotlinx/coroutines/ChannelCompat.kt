package kotlinx.coroutines

/** Temporary source-compat bridge for the NEO rebuild. */
typealias Channel<E> = kotlinx.coroutines.channels.Channel<E>

fun <E> Channel(capacity: Int = kotlinx.coroutines.channels.Channel.RENDEZVOUS): kotlinx.coroutines.channels.Channel<E> =
    kotlinx.coroutines.channels.Channel(capacity)
