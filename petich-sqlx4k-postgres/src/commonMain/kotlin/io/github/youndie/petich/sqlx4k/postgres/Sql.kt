package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement

/**
 * Parameters are **named only**.
 *
 * sqlx4k takes positional ones too, and in a statement with nine columns getting the order wrong is
 * a matter of time — silently, because the types line up and the values land in the wrong columns.
 */
internal fun sql(text: String): Statement = Statement.create(text)

/** How many rows the statement changed. */
internal suspend fun QueryExecutor.update(statement: Statement): Long = execute(statement).getOrThrow()

/** The rows the statement produced. */
internal suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
    fetchAll(statement)
        .getOrThrow()
        .rows

/**
 * A table name goes into the SQL text, so it is the one value here that cannot be a parameter.
 *
 * Checked rather than trusted: the name usually comes from a constant, but "usually" is what makes
 * an injection through a configuration key possible, and the check costs a comparison at
 * construction time.
 */
internal fun requireIdentifier(name: String) {
    require(name.isNotEmpty() && name.length <= 63) {
        "table name must be 1..63 characters, got ${name.length}"
    }
    require(name.all { it.isLetterOrDigit() || it == '_' } && !name.first().isDigit()) {
        "table name must be a plain SQL identifier (letters, digits, underscore, not starting with " +
            "a digit), got '$name'"
    }
}
