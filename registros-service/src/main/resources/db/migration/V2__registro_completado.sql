-- JornadaFinalizada now transitions every Registro CONFIRMADO of that Jornada
-- to COMPLETADO (see JornadasEventListener.onJornadaFinalizada).
ALTER TYPE registro_estado ADD VALUE 'COMPLETADO';
