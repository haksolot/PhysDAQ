import { app, shell, BrowserWindow, ipcMain } from 'electron'
import { join } from 'path'
import { electronApp, optimizer, is } from '@electron-toolkit/utils'
import icon from '../../resources/icon.png?asset'
import { startSidecar, getSerialPorts, scanBle } from './sidecar'
import { getNodeAliases, setNodeAlias } from './config'
import { flashNode, getFirmwareInfo, getBootloaderStatus } from './flasher'

function createWindow(): void {
  // Create the browser window.
  const mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    show: false,
    title: 'PhysDAQ',
    autoHideMenuBar: true,
    // Set on every platform, not just Linux: without it the Windows title bar
    // and taskbar fall back to the stock Electron icon during `npm run dev`,
    // since only the packaged .exe carries build/icon.ico. macOS ignores this
    // and takes its dock icon from the bundle.
    icon,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false
    }
  })

  mainWindow.on('ready-to-show', () => {
    mainWindow.show()
    startSidecar(mainWindow)
  })

  mainWindow.webContents.setWindowOpenHandler((details) => {
    shell.openExternal(details.url)
    return { action: 'deny' }
  })

  // HMR for renderer base on electron-vite cli.
  // Load the remote URL for development or the local html file for production.
  if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

// This method will be called when Electron has finished
// initialization and is ready to create browser windows.
// Some APIs can only be used after this event occurs.
app.whenReady().then(() => {
  // Set app user model id for windows
  electronApp.setAppUserModelId('ca.bcit.physdaq')

  // Default open or close DevTools by F12 in development
  // and ignore CommandOrControl + R in production.
  // see https://github.com/alex8088/electron-toolkit/tree/master/packages/utils
  app.on('browser-window-created', (_, window) => {
    optimizer.watchWindowShortcuts(window)
  })

  // IPC test
  ipcMain.on('ping', () => console.log('pong'))
  ipcMain.handle('get-serial-ports', () => getSerialPorts())
  ipcMain.handle('scan-ble', (_, all?: boolean) => scanBle(all))
  ipcMain.handle('get-node-aliases', () => getNodeAliases())
  ipcMain.handle('set-node-alias', (_, target: string, alias: string) =>
    setNodeAlias(target, alias)
  )
  ipcMain.handle('get-firmware-info', () => getFirmwareInfo())
  ipcMain.handle('get-bootloader-status', () => getBootloaderStatus())
  ipcMain.handle('flash-node', (event) =>
    flashNode(BrowserWindow.fromWebContents(event.sender))
  )

  createWindow()

  app.on('activate', function () {
    // On macOS it's common to re-create a window in the app when the
    // dock icon is clicked and there are no other windows open.
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

// Quit when all windows are closed, except on macOS. There, it's common
// for applications and their menu bar to stay active until the user quits
// explicitly with Cmd + Q.
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

// In this file you can include the rest of your app's specific main process
// code. You can also put them in separate files and require them here.
