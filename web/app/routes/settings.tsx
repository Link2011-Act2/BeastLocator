import { useNavigate } from "react-router";

export default function Settings() {
  const navigate = useNavigate();
  const lat = 35.665544
  const lng = 139.6699717
  return (
    <main style={{ paddingTop: "4rem" }}>
      <div style={{ position: "fixed", top: "1rem", left: "1rem" }}>
        <button className="circle" onClick={() => navigate(-1)}>
          <i>arrow_back</i>
        </button>
	<h5>設定</h5>
	<h6>現在の目的地</h6>
	<p>緯度: {lat} / 経度: {lng}</p> 
	<h6>到着通知</h6>
	<p>いい感じの到着通知とかを設定する文章とトグルと数値をlocalstorageに保管する処理を書く</p>
      </div> 
    </main>
  );
}
