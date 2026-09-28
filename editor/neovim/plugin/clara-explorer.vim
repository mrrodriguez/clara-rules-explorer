" clara-explorer.vim — user command registration (see lua/clara-explorer/init.lua).

if exists('g:loaded_clara_explorer')
  finish
endif
let g:loaded_clara_explorer = 1

command! -nargs=0 ClaraExplorerNavigateProducer lua require("clara-explorer").navigate("lhs")
command! -nargs=0 ClaraExplorerNavigateConsumer lua require("clara-explorer").navigate("rhs")
command! -nargs=0 ClaraExplorerRefresh lua require("clara-explorer").refresh()
command! -nargs=0 -bang ClaraExplorerSwapSession lua require("clara-explorer").swap_session(<bang>)
command! -nargs=0 ClaraExplorerToggleTransport lua require("clara-explorer").toggle_transport()
command! -nargs=0 ClaraExplorerTransportStatus lua require("clara-explorer").transport_status()
command! -nargs=0 ClaraExplorerLastError lua require("clara-explorer.conjure").show_last_error()
command! -nargs=0 ClaraExplorerSelectUnit lua require("clara-explorer").select_unit()
