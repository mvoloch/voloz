import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  useNavigate,
} from '@tanstack/react-router';
import { AppFrame } from '@/components/AppFrame/AppFrame';
import { CapturesTab } from '@/components/CapturesTab/CapturesTab';
import { EffectsTab } from '@/components/EffectsTab/EffectsTab';
import { LiteAboutPage } from '@/components/LiteSettings/LiteAboutPage';
import { LiteSettingsLayout } from '@/components/LiteSettings/LiteSettingsLayout';
import { LiteTopBar } from '@/components/LiteSettings/LiteTopBar';
import {
  LiteGeneralPage,
  LiteRefinementPage,
  LiteShortcutsPage,
  LiteTranscriptionPage,
  LiteVocabularyPage,
} from '@/components/LiteSettings/pages';
import { LiteSetupScreen, useLiteSetupGate } from '@/components/LiteSetup/LiteSetup';
import { MainEditor } from '@/components/MainEditor/MainEditor';
import { ModelsTab } from '@/components/ModelsTab/ModelsTab';
import { AboutPage } from '@/components/ServerTab/AboutPage';
import { CapturesPage } from '@/components/ServerTab/CapturesPage';
import { ChangelogPage } from '@/components/ServerTab/ChangelogPage';
import { GeneralPage } from '@/components/ServerTab/GeneralPage';
import { GenerationPage } from '@/components/ServerTab/GenerationPage';
import { GpuPage } from '@/components/ServerTab/GpuPage';
import { LogsPage } from '@/components/ServerTab/LogsPage';
import { MCPPage } from '@/components/ServerTab/MCPPage';
import { SettingsLayout } from '@/components/ServerTab/ServerTab';
import { Sidebar } from '@/components/Sidebar';
import { StoriesTab } from '@/components/StoriesTab/StoriesTab';
import { Toaster } from '@/components/ui/toaster';
import { VoicesTab } from '@/components/VoicesTab/VoicesTab';
import { IS_LITE } from '@/lib/edition';
import { useGenerationProgress } from '@/lib/hooks/useGenerationProgress';
import { useModelDownloadToast } from '@/lib/hooks/useModelDownloadToast';
import { MODEL_DISPLAY_NAMES, useRestoreActiveTasks } from '@/lib/hooks/useRestoreActiveTasks';

// Simple platform check that works in both web and Tauri
const isMacOS = () => navigator.platform.toLowerCase().includes('mac');

// Root layout component
function RootLayout() {
  // Monitor active downloads/generations and show toasts for them
  const activeDownloads = useRestoreActiveTasks();

  // Subscribe to SSE for pending generations — handles completion, auto-play, and history refresh
  useGenerationProgress();

  // Lite first run: one Groq key instead of the transcription and
  // refinement settings. Studio never shows it.
  const liteSetup = useLiteSetupGate();
  const navigate = useNavigate();

  return (
    <AppFrame>
      {liteSetup.show ? (
        <LiteSetupScreen
          onManual={() => {
            liteSetup.skip();
            navigate({ to: '/settings/captures' });
          }}
        />
      ) : liteSetup.pending ? (
        <div className="flex-1" />
      ) : IS_LITE ? (
        <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
          <LiteTopBar />
          <main className="flex-1 min-h-0 overflow-hidden">
            <Outlet />
          </main>
        </div>
      ) : (
        <div className="flex flex-1 min-h-0 overflow-hidden">
          <Sidebar isMacOS={isMacOS()} />

          <main className="flex-1 ml-[14.5rem] overflow-hidden flex flex-col">
            <div className="container mx-auto px-8 max-w-[1800px] h-full overflow-hidden flex flex-col">
              <Outlet />
            </div>
          </main>
        </div>
      )}

      {/* Show download toasts for any active downloads (from anywhere) */}
      {activeDownloads.map((download) => {
        const displayName = MODEL_DISPLAY_NAMES[download.model_name] || download.model_name;
        return (
          <DownloadToastRestorer
            key={download.model_name}
            modelName={download.model_name}
            displayName={displayName}
          />
        );
      })}

      <Toaster />
    </AppFrame>
  );
}

/**
 * Component that restores a download toast for a specific model.
 */
function DownloadToastRestorer({
  modelName,
  displayName,
}: {
  modelName: string;
  displayName: string;
}) {
  // Use the download toast hook to restore the toast
  useModelDownloadToast({
    modelName,
    displayName,
    enabled: true,
  });

  return null;
}

// Root route with layout
const rootRoute = createRootRoute({
  component: RootLayout,
});

// The lite edition has no voice studio: Studio pages redirect instead of
// rendering, so old links and bookmarks never call endpoints lite doesn't serve
const studioOnly = IS_LITE
  ? () => {
      throw redirect({ to: '/' });
    }
  : undefined;
const studioOnlySetting = IS_LITE
  ? () => {
      throw redirect({ to: '/settings' });
    }
  : undefined;
// Lite splits dictation settings into sections; Studio has no such pages
const liteOnlySetting = IS_LITE
  ? undefined
  : () => {
      throw redirect({ to: '/settings/captures' });
    };

// Index route: dictation home (capture history)
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: CapturesTab,
});

// Generate route (voice studio)
const generateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/generate',
  component: MainEditor,
  beforeLoad: studioOnly,
});

// Stories route
const storiesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/stories',
  component: StoriesTab,
  beforeLoad: studioOnly,
});

// Voices route
const voicesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/voices',
  component: VoicesTab,
  beforeLoad: studioOnly,
});

// Old captures path: the capture history is now the home screen
const capturesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/captures',
  beforeLoad: () => {
    throw redirect({ to: '/' });
  },
});

// Effects route
const effectsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/effects',
  component: EffectsTab,
  beforeLoad: studioOnly,
});

// Models route
const modelsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/models',
  component: ModelsTab,
  beforeLoad: studioOnly,
});

// Settings layout route (parent for sub-tabs)
const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings',
  component: IS_LITE ? LiteSettingsLayout : SettingsLayout,
});

// Settings sub-routes
const settingsGeneralRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/',
  component: IS_LITE ? LiteShortcutsPage : GeneralPage,
});

const settingsTranscriptionRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/transcription',
  component: LiteTranscriptionPage,
  beforeLoad: liteOnlySetting,
});

const settingsRefinementRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/refinement',
  component: LiteRefinementPage,
  beforeLoad: liteOnlySetting,
});

const settingsVocabularyRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/vocabulary',
  component: LiteVocabularyPage,
  beforeLoad: liteOnlySetting,
});

const settingsLiteGeneralRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/general',
  component: LiteGeneralPage,
  beforeLoad: liteOnlySetting,
});

const settingsGenerationRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/generation',
  component: GenerationPage,
  beforeLoad: studioOnlySetting,
});

const settingsCapturesRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/captures',
  component: CapturesPage,
  // Lite's old all-in-one page (the first-run "set it up myself" link)
  beforeLoad: IS_LITE
    ? () => {
        throw redirect({ to: '/settings/transcription' });
      }
    : undefined,
});

const settingsMCPRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/mcp',
  component: MCPPage,
  beforeLoad: studioOnlySetting,
});

const settingsGpuRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/gpu',
  component: GpuPage,
  beforeLoad: studioOnlySetting,
});

const settingsChangelogRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/changelog',
  component: ChangelogPage,
});

const settingsLogsRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/logs',
  component: LogsPage,
});

const settingsAboutRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/about',
  component: IS_LITE ? LiteAboutPage : AboutPage,
});

// Redirect old /server path to /settings
const serverRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/server',
  beforeLoad: () => {
    throw redirect({ to: '/settings' });
  },
});

// Route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  generateRoute,
  storiesRoute,
  capturesRoute,
  voicesRoute,
  effectsRoute,
  modelsRoute,
  settingsRoute.addChildren([
    settingsGeneralRoute,
    settingsTranscriptionRoute,
    settingsRefinementRoute,
    settingsVocabularyRoute,
    settingsLiteGeneralRoute,
    settingsGenerationRoute,
    settingsCapturesRoute,
    settingsMCPRoute,
    settingsGpuRoute,
    settingsLogsRoute,
    settingsChangelogRoute,
    settingsAboutRoute,
  ]),
  serverRedirectRoute,
]);

// Create router
export const router = createRouter({ routeTree });

// Register router for type safety
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
