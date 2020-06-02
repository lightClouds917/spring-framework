### Spring源码解析 by IT云清 
DataSourceTransactionManagerTests


JdbcTransactionObjectSupport 持有ConnectionHolder
ConnectionHolder 持有ConnectionHandle和Connection
afterCompletion中，会this.resourceHolder.unbound()，这里会把ResourceHolderSupport isVoid设置为无效状态。


### TOTO LIST
1.TimerTask的使用；
