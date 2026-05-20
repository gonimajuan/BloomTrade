import { Link } from 'react-router-dom';

/**
 * /login — stub para el redirect post-201 de HU-F01. El login real llega en HU-F02; este
 * placeholder evita que el redirect 1.5s después del registro caiga en 404.
 */
export function LoginPage() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 px-4 text-slate-100">
      <div className="rounded-md border border-slate-700 bg-slate-900 p-8 text-center">
        <h1 className="text-xl font-semibold">Iniciar sesión</h1>
        <p className="mt-2 text-sm text-slate-400">
          Funcionalidad pendiente — HU-F02. Por ahora podés{' '}
          <Link to="/register" className="text-blue-400 hover:text-blue-300">
            crear una cuenta
          </Link>
          .
        </p>
      </div>
    </main>
  );
}
