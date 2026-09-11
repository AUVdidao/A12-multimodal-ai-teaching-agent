const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('lessonForgeDesktop', Object.freeze({ isDesktop: true }));
