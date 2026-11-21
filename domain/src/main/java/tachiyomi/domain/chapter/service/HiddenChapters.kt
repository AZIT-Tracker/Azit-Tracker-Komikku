package tachiyomi.domain.chapter.service

// Azit -->
/**
 * Leaves out the chapters hidden one by one (selection menu -> Hide), unless the series shows them ([showHidden]).
 * [keepId] always stays (the chapter being opened or read, even if hidden). The order is unchanged.
 */
fun <T> List<T>.withoutHiddenChapters(
    hiddenIds: Set<Long>,
    showHidden: Boolean = false,
    keepId: Long? = null,
    idOf: (T) -> Long,
): List<T> {
    if (showHidden || hiddenIds.isEmpty()) return this
    return filterNot { item -> idOf(item).let { it in hiddenIds && it != keepId } }
}
// Azit <--
