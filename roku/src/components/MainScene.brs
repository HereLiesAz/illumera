sub init()
    m.views = m.top.FindNode("views")
    m.stack = []
    m.top.backgroundColor = "0x000000FF"
    m.top.backgroundUri = ""
    EnsureDefaults()
end sub

' First run: Cinemeta for catalogs and details, OpenSubtitles for subtitles.
sub EnsureDefaults()
    if Addons().Count() > 0 then
        Open("HomeView", {})
        return
    end if
    m.pending = ["https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io"]
    m.failed = 0
    InstallNext()
end sub

sub InstallNext()
    if m.pending.Count() = 0 then
        Open("HomeView", {})
        if m.failed > 0 then WarnOffline()
        return
    end if
    m.installer = CreateObject("roSGNode", "HttpTask")
    m.installer.ObserveField("response", "OnDefaultInstalled")
    m.installer.request = { kind: "install", url: m.pending.Shift() }
    m.installer.control = "run"
end sub

sub OnDefaultInstalled(event as Object)
    res = event.GetData()
    if res = invalid or not res.ok then m.failed = m.failed + 1
    InstallNext()
end sub

' First run without a network: say why Home is empty. The defaults are tried again next launch.
sub WarnOffline()
    dialog = CreateObject("roSGNode", "Dialog")
    dialog.title = "Couldn't reach the addon servers"
    dialog.message = "Check the Roku's network connection, then restart illumera to install Cinemeta and OpenSubtitles. You can also add addons under Addons."
    dialog.buttons = ["OK"]
    dialog.ObserveField("buttonSelected", "CloseWarning")
    m.top.dialog = dialog
    m.warning = dialog
end sub

sub CloseWarning()
    m.warning.close = true
end sub

sub Open(name as String, params as Object)
    if m.stack.Count() > 0 then m.stack.Peek().visible = false
    view = CreateObject("roSGNode", name)
    view.ObserveField("navigate", "OnNavigate")
    m.views.AppendChild(view)
    m.stack.Push(view)
    view.params = params
    view.SetFocus(true)
end sub

sub OnNavigate(event as Object)
    nav = event.GetData()
    if nav = invalid or nav.to = invalid then return
    if nav.to = "back" then
        Back()
    else
        Open(nav.to, nav.params)
    end if
end sub

function Back() as Boolean
    if m.stack.Count() <= 1 then return false
    view = m.stack.Pop()
    m.views.RemoveChild(view)
    top = m.stack.Peek()
    top.visible = true
    top.resumed = true
    top.SetFocus(true)
    return true
end function

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press and key = "back" then return Back()
    return false
end function
