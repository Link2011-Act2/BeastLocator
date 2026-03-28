import { useState, useEffect } from "react";
import { useNavigate } from "react-router";

export function Welcome() {
  //ダークモード、匿名モード(仮)の切り替え
  const [dark, setDark] = useState(false);
  const [anon, setAnon] = useState(false);

  const navigate = useNavigate();

　//ダークモード切り替え関連の処理
  useEffect(() => {
    const saved = localStorage.getItem("theme");
    if (saved !== null) {
      setDark(saved === "dark");
    } else {
      const mq = window.matchMedia("(prefers-color-scheme: dark)");
      setDark(mq.matches);
      const handler = (e: MediaQueryListEvent) => setDark(e.matches);
      mq.addEventListener("change", handler);
      return () => mq.removeEventListener("change", handler);
    }
  }, []);

  useEffect(() => {
    document.body.classList.toggle("dark", dark);
  }, [dark]);


  return (
    <main>
        <div style={{ position: "fixed", top: "1rem", right: "1rem", display: "flex", gap: "0.5rem" }}>
	  <button className="circle" onClick={() => {
	    const next = !dark;
	    setDark(next);
	    localStorage.setItem("theme", next ? "dark" : "light");
	  }}>
	    <i>{dark ? "light_mode" : "dark_mode"}</i>
	  </button>
	  <button className="circle" onClick={() => setAnon(!anon)}>
	    <i>{anon ? "visibility" : "visibility_off"}</i>
	  </button>
          <button className="circle" onClick={() => navigate("/settings")}>
            <i>settings</i>
          </button>
        </div>
      </main>
  );
};

//野獣邸の住所
const lat = 35.665544
const lng = 139.6699717
