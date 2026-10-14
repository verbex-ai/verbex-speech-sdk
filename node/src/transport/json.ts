/**
 * Lenient readers for API bodies (spec §6): a field of the wrong type reads as absent.
 */

export const obj = (v: unknown): Record<string, any> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, any>) : {});
export const arr = (v: unknown): any[] => (Array.isArray(v) ? v : []);
export const str = (v: unknown): string => (typeof v === "string" ? v : v == null ? "" : String(v));
export const optStr = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined);
export const nullStr = (v: unknown): string | null => (typeof v === "string" ? v : null);
export const optNum = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
export const num = (v: unknown): number => optNum(v) ?? 0;
