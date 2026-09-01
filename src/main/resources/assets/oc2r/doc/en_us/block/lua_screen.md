# Lua Screen
![160 by 50](block:oc2r:lua_screen)

An external display for a [Lua Computer](lua_computer.md). A tier 3 screen: 160 by 50 characters, 256 colours, and a keyboard of its own.

A computer has a screen built in, so this is not required to use one. It is what you place when one display is not enough - a bigger view of the same machine, a wall of them, or a screen somewhere the computer is not.

### Connecting one
Place it directly against the computer, on any side. There is no cable and no bus interface; touching is the connection. A computer sees every screen it is touching, and a screen delivers what is typed at it to every computer it is touching.

### Using it
Right click to open the terminal window - the same one the computer opens, showing the same machine. The face of the block shows what is on the screen, but 160 columns is unreadable from where you would stand.

The two buttons down the left of that window are the power button, which switches the attached computer on and off, and the input button, which decides who gets your keystrokes. Input goes to the machine while that button is on **and** the pointer is over the screen; move the pointer away and Escape closes the window.

Clicks, drags and scrolling on the screen reach the program as touch, drag and scroll events, which is what a graphical operating system listens for. Ctrl+V pastes from the clipboard.

### Two modes
A screen shows either characters or pixels, and follows whichever card drew to it last: a `gpu` call puts the terminal back up, a `canvas` call puts the drawing back up. Neither buffer is cleared by the other, so a program can put up a splash screen and hand back the terminal exactly as it was.
