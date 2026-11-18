package nl.stockio.stock.domain.port

interface StockScopeRepository {
    fun findSkus(): List<String>
}
