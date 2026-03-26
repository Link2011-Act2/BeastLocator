import { useState, useEffect } from "react";

export function Welcome() {
  const [dark, setDark] = useState(false);
  const [anon, setAnon] = useState(false)

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    setDark(mq.matches);

    const handler = (e: MediaQueryListEvent) => setDark(e.matches);
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, []);

  useEffect(() => {
    document.body.classList.toggle("dark", dark);
  }, [dark]);


  return (
    <main>
        <div style={{ position: "fixed", top: "1rem", right: "1rem", display: "flex", gap: "0.5rem" }}>
	
          <button className="circle" onClick={() => setDark(!dark)}>
            <i>{dark ? "light_mode" : "dark_mode"}</i>
          </button>

<button className="circle" onClick={() => setAnon(!anon)}>
  <i>{anon ? "visibility" : "visibility_off"}</i>
</button>
          <button className="circle">
	    <i>settings</i>
	  </button>
        </div>
      </main>
  );
};

const lat = 35.665544
const lng = 139.6699717
