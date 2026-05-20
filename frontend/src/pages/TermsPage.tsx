import { Link } from 'react-router-dom';

/** /terms — placeholder legal (spec HU-F01 §12.4 — contenido real fuera del MVP). */
export function TermsPage() {
  return (
    <main className="min-h-screen bg-slate-950 px-4 py-12 text-slate-100">
      <div className="mx-auto max-w-2xl">
        <h1 className="text-2xl font-bold">Términos y condiciones de BloomTrade</h1>
        <p className="mt-4 text-slate-300">
          Términos y condiciones de BloomTrade — pendientes de definición legal. Este es un
          texto placeholder para el MVP académico.
        </p>
        <Link
          to="/register"
          className="mt-6 inline-block text-blue-400 hover:text-blue-300"
        >
          ← Volver al registro
        </Link>
      </div>
    </main>
  );
}
