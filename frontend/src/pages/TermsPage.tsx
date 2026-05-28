import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowLeft } from 'lucide-react';
import { Card } from '@/components/ui/Card';

/** /terms — placeholder legal (spec HU-F01 §12.4 — contenido real fuera del MVP). */
export function TermsPage() {
  return (
    <main className="flex min-h-screen items-start justify-center px-4 py-12">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="w-full max-w-2xl"
      >
        <Card variant="glass" className="p-8">
          <h1 className="text-2xl font-semibold tracking-tight text-white">
            Términos y condiciones de BloomTrade
          </h1>
          <p className="mt-4 text-sm leading-relaxed text-slate-300">
            Términos y condiciones de BloomTrade — pendientes de definición legal.
            Este es un texto placeholder para el MVP académico.
          </p>
          <Link
            to="/register"
            className="mt-6 inline-flex items-center gap-1.5 text-sm font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Volver al registro
          </Link>
        </Card>
      </motion.div>
    </main>
  );
}
