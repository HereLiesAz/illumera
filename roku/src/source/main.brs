' illumera for Roku: browse, search and play from Stremio-compatible addons.
' The plan is in docs/PLATFORMS.md (step 7); behavior follows docs/ADDONS.md.

sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.Show()
    while true
        msg = Wait(0, port)
        if Type(msg) = "roSGScreenEvent" and msg.IsScreenClosed() then return
    end while
end sub
