'use strict';
const { contextBridge, ipcRenderer } = require('electron');
if (window.top === window) contextBridge.exposeInMainWorld('androlog', Object.freeze({
  platform: process.platform,
  settings: () => ipcRenderer.invoke('settings:get'), chooseSetting: kind => ipcRenderer.invoke('settings:choose', kind), saveSettings: value => ipcRenderer.invoke('settings:save', value),
  chooseApk: () => ipcRenderer.invoke('select:apk'), chooseLog: () => ipcRenderer.invoke('select:log'), customPlans: tag => ipcRenderer.invoke('custom:plans', tag), readCustom: (tag, planId) => ipcRenderer.invoke('log:custom', tag, planId),
  inspectCode: () => ipcRenderer.invoke('code:inspect'), instrument: (options, plan) => ipcRenderer.invoke('apk:instrument', options, plan),
  saveApk: () => ipcRenderer.invoke('apk:save'), analyze: (options, planId) => ipcRenderer.invoke('coverage:analyze', options, planId), saveReport: () => ipcRenderer.invoke('coverage:save'),
  cancel: () => ipcRenderer.invoke('operation:cancel'), onUpdate: handler => ipcRenderer.on('operation:update', (_event, value) => handler(value))
}));
