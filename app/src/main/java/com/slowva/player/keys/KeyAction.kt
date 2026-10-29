package com.slowva.player.keys

/** What a hardware key can do. [ui] actions go to [KeyRouter.uiActions]; the rest run directly. */
enum class KeyAction(val label: String, val ui: Boolean = false, val repeats: Boolean = false) {
    PLAY_PAUSE("Play / pause"),
    NEXT("Next song"),
    PREVIOUS("Previous song"),
    SEEK_FORWARD("Forward 10 s", repeats = true),
    SEEK_BACK("Back 10 s", repeats = true),
    VOLUME_UP("Volume up", repeats = true),
    VOLUME_DOWN("Volume down", repeats = true),
    LIKE("Like song"),
    ADD_TO_PLAYLIST("Add to playlist", ui = true),
    SEARCH("Search", ui = true),
    QUEUE("Queue", ui = true),
    OPEN_PLAYER("Open player", ui = true),
    CLOSE("Back / close", ui = true),
    TAB_1("Tab 1", ui = true),
    TAB_2("Tab 2", ui = true),
    TAB_3("Tab 3", ui = true),
    TAB_4("Tab 4", ui = true),
}
