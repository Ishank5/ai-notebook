import { lazy, Suspense } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Toaster } from "sonner";
import Shell from "./components/Shell";
import { AuthProvider, useAuth } from "./lib/auth";
import SignIn, { NoAccess, Splash } from "./pages/SignIn";

const Dashboard = lazy(() => import("./features/dashboard/Dashboard"));
const Recordings = lazy(() => import("./features/recordings/Recordings"));
const RecordingDetail = lazy(() => import("./features/recordings/RecordingDetail"));
const RecordingSettings = lazy(() => import("./features/recordings/RecordingSettings"));
const Tasks = lazy(() => import("./features/tasks/Tasks"));
const Ask = lazy(() => import("./features/ask/Ask"));
const page = (Page) => <Suspense fallback={null}><Page /></Suspense>;

function Gate() {
  const { status, can } = useAuth();
  if (status === "loading") return <Splash />;
  if (status === "signed_out") return <SignIn />;
  if (status !== "ready") return <NoAccess status={status} />;

  // Members see their own notes and tasks; admins see everyone's and the settings. The API checks both.
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={page(Dashboard)} />
        <Route path="recordings" element={page(Recordings)} />
        <Route path="recordings/:recordingId" element={page(RecordingDetail)} />
        <Route path="notes" element={<Navigate to="/recordings" replace />} />
        <Route path="tasks" element={page(Tasks)} />
        <Route path="ask" element={page(Ask)} />
        <Route path="settings" element={can("recordings.manage") ? page(RecordingSettings) : <Navigate to="/" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Gate />
      </BrowserRouter>
      <Toaster position="top-center" theme="light" toastOptions={{ style: { fontFamily: "inherit", borderRadius: 16 } }} />
    </AuthProvider>
  );
}
