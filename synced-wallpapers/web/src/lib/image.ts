import { AppError } from "./errors";

export const MAX_INPUT = 20 * 1024 * 1024;
const LONG_EDGE = 2560;
const MAX_BYTES = 2 * 1024 * 1024 - 64 * 1024;
const ACCEPTED = ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"];

/**
 * Re-encodes the picked photo like the Android app: long edge ≤ 2560 px, JPEG < 2 MB.
 * Drawing to a canvas and re-encoding drops all EXIF/location metadata.
 */
export async function prepareUpload(file: File): Promise<{ blob: Blob; url: string; width: number; height: number }> {
  if (file.type && !ACCEPTED.includes(file.type.toLowerCase())) throw new AppError("Use a JPEG, PNG, WebP or HEIC photo.");
  if (file.size > MAX_INPUT) throw new AppError("That photo is larger than 20 MB.");
  let bmp: ImageBitmap;
  try {
    bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    throw new AppError(/heic|heif/i.test(file.type || file.name) ? "This browser can't open HEIC photos. Use JPEG or PNG, or propose from the phone app." : "Couldn't read that photo.");
  }
  let scale = Math.min(1, LONG_EDGE / Math.max(bmp.width, bmp.height));
  for (let attempt = 0; attempt < 8; attempt++) {
    const w = Math.round(bmp.width * scale), h = Math.round(bmp.height * scale);
    const canvas = document.createElement("canvas");
    canvas.width = w; canvas.height = h;
    canvas.getContext("2d")!.drawImage(bmp, 0, 0, w, h);
    for (const q of [0.9, 0.8, 0.7, 0.6]) {
      const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, "image/jpeg", q));
      if (blob && blob.size <= MAX_BYTES) { bmp.close(); return { blob, url: URL.createObjectURL(blob), width: w, height: h }; }
    }
    scale *= 0.85;
  }
  bmp.close();
  throw new AppError("Couldn't make that photo small enough. Try another one.");
}
