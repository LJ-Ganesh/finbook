type Props = { url?: string | null; focalX?: number; focalY?: number; reminder?: string | null; style?: number; width?: number };

/**
 * The photo in a phone-shaped frame, cropped around the focal point the way phones crop
 * it, with the clock area marked. Rendered as a CSS background with right-click and
 * dragging disabled. (A browser can't fully prevent screenshots; the phone app adds
 * FLAG_SECURE.)
 */
export function PhoneFrame({ url, focalX = 0.5, focalY = 0.5, reminder, style = 0, width = 240 }: Props) {
  return (
    <div className="phone" style={{ width }} onContextMenu={(e) => e.preventDefault()} onDragStart={(e) => e.preventDefault()}>
      {url && <div className="photo" style={{ backgroundImage: `url("${url}")`, backgroundPosition: `${focalX * 100}% ${focalY * 100}%` }} />}
      <div className="clockzone" />
      <div className="clock">12:45</div>
      {reminder && <div className={`reminder s${style}`}>{reminder}</div>}
    </div>
  );
}
