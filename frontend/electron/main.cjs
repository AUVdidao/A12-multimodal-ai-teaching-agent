const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('node:path');

// Electron defaults to a generic "Electron" profile in development mode. That
// profile can be shared by stale or unrelated dev launches, which makes the
// single-instance lock and window focus behavior unreliable. Give LessonForge
// a stable application identity before acquiring the lock.
app.setName('LessonForge');
app.setPath('userData', path.join(app.getPath('appData'), 'LessonForge'));

const singleInstanceLock = app.requestSingleInstanceLock();
if (!singleInstanceLock) {
  app.quit();
}

let mainWindow;

function focusMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  if (!mainWindow.isVisible()) mainWindow.show();
  mainWindow.focus();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1100,
    minHeight: 720,
    backgroundColor: '#f4f7fb',
    title: 'LessonForge',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  const devUrl = process.env.LESSONFORGE_DEV_URL;
  const loadPromise = devUrl
    ? mainWindow.loadURL(devUrl)
    : mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  loadPromise.catch((error) => {
    dialog.showErrorBox(
      'LessonForge 启动失败',
      `桌面窗口已创建，但页面加载失败。请先确认本地服务已启动。\n\n${error.message}`,
    );
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) void shell.openExternal(url);
    return { action: 'deny' };
  });
}

if (singleInstanceLock) {
  app.on('second-instance', () => {
    focusMainWindow();
  });

  app.whenReady().then(() => {
    createWindow();
    mainWindow.once('ready-to-show', () => focusMainWindow());
    app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
  });
}

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
