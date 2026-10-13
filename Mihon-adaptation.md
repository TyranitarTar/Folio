# Folio 0.5 Mihon adaptation

Mihon UI patterns adapted for Folio. Copyright Mihon contributors.
Source: user-supplied mihon-main.zip; https://github.com/mihonapp/mihon
Archive SHA-256: dacc5b4ccdb9623da98842ec06f8789141999ca28f4d83ca659c923090bb2db5

Folio adapts home tabs, cover-grid/list presentation, category/shelf navigation, library filter/sort/display sheets, selection actions, reading history and a book details/chapters view. Folio retains its Readium EPUB engine and offline speech; no Mihon extension/network subsystem is bundled.


The provided source was reviewed at HomeScreen.kt, LibraryTab.kt, LibraryContent.kt and LibraryToolbar.kt. Folio implements compatible patterns with its existing local EPUB model, without adding Voyager, Mihon dependency injection, extension repositories or network permission.

Persistent preferences include display mode/columns, sort, filters and app theme. Shelves are optional backward-compatible fields in each book record. Library, History, Browse and More are functional local tabs. Book details expose native EPUB chapters and existing annotations.
