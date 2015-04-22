package lighthouse.utils

// Type system hack for reflectively set properties to override the nullability checks
fun <T> later(): T = null as T