package ru.workinprogress.petich

// A test stand-in for "the one-time code the user types back", used by the confirmation scenarios
// in these tests. petich-core depends on no application, so the tests declare their own payload
// types, as they do for the operation payloads themselves.
data class ConfirmResumePayload(
    val code: String,
) : ResumePayload()
