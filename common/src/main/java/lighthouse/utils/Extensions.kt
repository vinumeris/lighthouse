package lighthouse.utils

import org.bitcoinj.core.Coin

fun Coin.plus(other: Coin) = this.add(other)
fun Coin.minus(other: Coin) = this.subtract(other)
fun Long.asCoin(): Coin = Coin.valueOf(this)