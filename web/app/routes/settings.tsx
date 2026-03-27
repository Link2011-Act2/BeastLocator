import { useNavigate } from "react-router";

export default function Settings() {
  const navigate = useNavigate();

  return (
    <main style={{ paddingTop: "4rem" }}>
      <div style={{ position: "fixed", top: "1rem", left: "1rem" }}>
        <button className="circle" onClick={() => navigate(-1)}>
          <i>arrow_back</i>
        </button>
      </div>
      <h5>設定</h5>
    </main>
  );
}
