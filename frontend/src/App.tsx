function App() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex items-center justify-center px-6">
      <div className="text-center space-y-6 max-w-xl">
        <h1 className="text-5xl font-bold tracking-tight">BloomTrade</h1>
        <p className="text-lg text-slate-300">Bootstrap OK · Día 0</p>
        <div className="text-sm text-slate-400 space-y-1">
          <p>Universidad El Bosque · Ing. de Software 2 · 2026</p>
          <p>
            Backend:{' '}
            <a href="http://localhost:8080/swagger-ui.html" className="underline hover:text-slate-200">
              Swagger UI
            </a>
            {' · '}
            <a href="http://localhost:8080/actuator/health" className="underline hover:text-slate-200">
              Health
            </a>
          </p>
          <p>
            Observabilidad:{' '}
            <a href="http://localhost:5601" className="underline hover:text-slate-200">
              Kibana
            </a>
            {' · '}
            <a href="http://localhost:8025" className="underline hover:text-slate-200">
              MailHog
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}

export default App;
