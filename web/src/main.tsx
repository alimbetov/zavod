import React from 'react';
import { createRoot } from 'react-dom/client';
import '@xyflow/react/dist/style.css';
import './styles.css';
import { Workspace } from './workspace';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode><Workspace/></React.StrictMode>
);
