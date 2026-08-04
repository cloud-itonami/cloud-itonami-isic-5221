/**
 * open-rail kotoba — barrel.
 *
 * Per ADR-2605203000 Option B. Rail operations registry on the etzhayyim
 * substrate (AT PDS records; no RW).
 *
 *   station : defineStation / getStation / listStations
 *   line    : defineLine (ordered station sequence) / getLine / listLines
 *   run     : scheduleTrain / recordRunStatus / getRun / listTrainRuns
 *   coverage
 */

export * from "./types.js";
export {
  defineStation,
  getStation,
  listStations,
  defineLine,
  getLine,
  listLines,
  scheduleTrain,
  recordRunStatus,
  getRun,
  listTrainRuns,
  coverage,
} from "./registry.js";
