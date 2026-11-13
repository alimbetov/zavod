import {defineConfig,devices} from '@playwright/test';

export default defineConfig({
  testDir:'./e2e',
  timeout:30000,
  fullyParallel:true,
  retries:0,
  reporter:'list',
  use:{baseURL:'http://127.0.0.1:5173',trace:'retain-on-failure'},
  projects:[{name:'chromium',use:{...devices['Desktop Chrome'],viewport:{width:1440,height:900}}}],
  webServer:{
    command:'npm run dev',
    url:'http://127.0.0.1:5173',
    reuseExistingServer:!process.env.CI,
    timeout:60000
  },
  outputDir:'test-results'
});
