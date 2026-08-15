package ru.workinprogress.petich

// The INTENT to publish an event, attached to an interceptor's result (see
// InterceptorResult.Proceed.outboxEvents). PetichEngine persists it in the same SQL transaction as
// the petich update itself (see OutboxAwarePetichRepository), so the database write and the intent
// to notify an external system either both commit or both roll back — which makes a dual write
// between a business mutation and a network effect (push, webhook) structurally impossible.
// Delivering the event (a relay worker, at-least-once) is :petich-outbox-core's separate concern;
// petich-core knows nothing about it.
interface OutboxEvent {
    val id: String
    val type: String
    val payload: String // pre-serialised JSON — the interceptor does the serialising itself
}
