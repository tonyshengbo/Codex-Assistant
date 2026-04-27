package com.auracode.assistant.test

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.provider.session.ProviderProtocolDomainMapper
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.session.kernel.SessionDomainEvent
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** Maps a unified test event sequence into session-domain events using the production ingress mapper. */
internal fun mapProviderEvents(events: Iterable<ProviderEvent>): List<SessionDomainEvent> {
    val mapper = ProviderProtocolDomainMapper()
    return events.flatMap(mapper::map)
}

/** Creates one session-domain flow from a unified-event flow block for test doubles. */
internal fun providerEventFlow(block: suspend FlowCollector<ProviderEvent>.() -> Unit): Flow<SessionDomainEvent> {
    return flow {
        val mapper = ProviderProtocolDomainMapper()
        val collector = object : FlowCollector<ProviderEvent> {
            override suspend fun emit(value: ProviderEvent) {
                mapper.map(value).forEach { domainEvent ->
                    this@flow.emit(domainEvent)
                }
            }
        }
        collector.block()
    }
}

/** Returns an empty session-domain flow while keeping fake providers concise in tests. */
internal fun emptySessionDomainEventFlow(): Flow<SessionDomainEvent> = emptyFlow()

/** Emits one unified event into a session-domain callbackFlow or channelFlow test double. */
internal fun trySendProviderEvent(
    scope: SendChannel<SessionDomainEvent>,
    event: ProviderEvent,
) {
    val mapper = ProviderProtocolDomainMapper()
    mapper.map(event).forEach { domainEvent ->
        scope.trySend(domainEvent)
    }
}

/** Builds one history page by replaying unified fixture events through the production mapper. */
internal fun historyPageFromProviderEvents(
    events: List<ProviderEvent>,
    hasOlder: Boolean = false,
    olderCursor: String? = null,
): ConversationHistoryPage {
    return ConversationHistoryPage(
        events = mapProviderEvents(events),
        hasOlder = hasOlder,
        olderCursor = olderCursor,
    )
}
