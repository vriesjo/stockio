package nl.stockio.allocation.domain.api.validator

class AllocationRequestException(message: String) : IllegalArgumentException(message) {

    private val violations: MutableMap<String, String> = mutableMapOf()

    constructor(message: String, violations: Map<String, String>) : this(message) {
        this.violations.putAll(violations)
    }

}
