package nl.stockio.allocation.validator

class AllocationRequestException(message: String) : RuntimeException(message) {

    private val violations: MutableMap<String, String> = mutableMapOf()

    constructor(message: String, violations: Map<String, String>) : this(message) {
        this.violations.putAll(violations)
    }

}
