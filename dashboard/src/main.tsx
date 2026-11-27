import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, ArrowDownToLine, Beaker, Check, CheckCircle2, ChevronDown, Circle, CircleAlert, Clock3, Cpu, Database, FileText, FlaskConical, Image, Layers3, ListChecks, LoaderCircle, Monitor, MousePointer2, Play, Radio, RefreshCw, Search, Settings2, ShieldCheck, Square, Video, Wifi, X } from 'lucide-react';
import { Chart } from './Chart';
import { number, statusText, verdictText, configLabel } from './format';
import { terminal } from './types';
import type { Action, Method, Metric, Preflight, Progress, Run, Trial, Viewport } from './types';
import './style.css';

let sessionToken = '';
async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...options, headers: { ...(options.body instanceof Blob ? {} : { 'Content-Type': 'application/json' }), 'X-Lab-Token': sessionToken, ...options.headers } });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(typeof error.detail === 'string' ? error.detail : `Yêu cầu thất bại (${response.status})`);
  }
  return response.json();
}
const artifactUrl = (run: Run, name: string) => `/api/runs/${run.id}/artifacts/${name.split('/').map(encodeURIComponent).join('/')}`;
const icons: Record<string, typeof Activity> = { 'Dữ liệu': Database, 'Hình ảnh': Image, 'Mạng': Wifi, 'Giao diện': Layers3, 'Điều phối': Settings2, 'Tổng hợp': Activity, 'Mở rộng': Beaker };

function App() {
  const [methods, setMethods] = useState<Method[]>([]);
  const [metrics, setMetrics] = useState<Record<string, Metric>>({});
  const [selected, setSelected] = useState('paging');
  const [caseId, setCaseId] = useState('default');
  const [network, setNetwork] = useState('good');
  const [device, setDevice] = useState('');
  const [preflight, setPreflight] = useState<Preflight>();
  const [checking, setChecking] = useState(false);
  const [runs, setRuns] = useState<Run[]>([]);
  const [viewRunId, setViewRunId] = useState('');
  const [activeRunId, setActiveRunId] = useState('');
  const [progress, setProgress] = useState<Progress[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [inspect, setInspect] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedMetric, setSelectedMetric] = useState('');
  const [capture, setCapture] = useState<MediaStream>();
  const [videoRatio, setVideoRatio] = useState(488/1092);
  const [viewport, setViewport] = useState<Viewport>();
  const [calibrating, setCalibrating] = useState(false);
  const [firstCorner, setFirstCorner] = useState<{ x: number; y: number }>();
  const [action, setAction] = useState<Action>();
  const [replay, setReplay] = useState<{ run: Run; trial: Trial }>();
  const [replayActions, setReplayActions] = useState<Action[]>([]);
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream>();
  const viewportRef = useRef<Viewport>();
  const activeRef = useRef('');
  const recording = useRef<{ recorder: MediaRecorder; stop: () => Promise<void>; trialId: string }>();
  const currentMethod = methods.find(m => m.id === selected);
  const currentCase = currentMethod?.cases.find(c => c.id === caseId);
  const viewRun = runs.find(r => r.id === viewRunId && r.method_id === selected) ?? runs.find(r => r.method_id === selected && r.case_id === caseId && r.network === network);
  const activeRun = runs.find(r => r.id === activeRunId);
  const chartMetric = selectedMetric && currentMethod?.metrics.includes(selectedMetric) ? selectedMetric : currentMethod?.metrics[0];
  const busy = Boolean(activeRunId);
  const canRun = Boolean(currentMethod?.enabled && preflight?.ready && device && capture && viewport && !busy && !loading);

  const refreshRuns = useCallback(async () => {
    const data = await api<Run[]>('/api/runs');
    setRuns(data);
    const active = data.find(r => !terminal(r.status));
    setActiveRunId(active?.id ?? '');
    activeRef.current = active?.id ?? '';
  }, []);

  const check = useCallback(async () => {
    if (activeRef.current) return;
    setChecking(true);
    try {
      const value = await api<Preflight>('/api/preflight');
      setPreflight(value);
      setDevice(old => value.devices.some(d => d.serial === old) ? old : value.devices[0]?.serial ?? '');
    } catch (e) { setError(String(e)); }
    finally { setChecking(false); }
  }, []);

  useEffect(() => {
    (async () => {
      sessionToken = (await api<{ token: string }>('/api/session')).token;
      const catalog = await api<{ methods: Method[]; metrics: Record<string, Metric> }>('/api/methods');
      setMethods(catalog.methods); setMetrics(catalog.metrics);
      await refreshRuns();
      await check();
    })().catch(e => setError(String(e)));
    return () => { streamRef.current?.getTracks().forEach(track => track.stop()); };
  }, [check, refreshRuns]);

  const stopRecording = useCallback(async () => {
    const record = recording.current;
    if (record) { recording.current = undefined; await record.stop(); }
  }, []);

  const beginRecording = useCallback(async (runId: string, trial: Trial) => {
    const stream = streamRef.current;
    if (!stream || !viewportRef.current || !stream.getVideoTracks().some(t => t.readyState === 'live')) throw new Error('Mất chia sẻ cửa sổ emulator');
    if (recording.current?.trialId === trial.trial_id) return;
    await stopRecording();
    const mimeType = ['video/webm;codecs=vp8', 'video/webm'].find(type => MediaRecorder.isTypeSupported(type));
    if (!mimeType) throw new Error('Trình duyệt không hỗ trợ ghi WebM; dùng Chrome hoặc Edge');
    const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 2_000_000 });
    let sequence = 0;
    let uploads = Promise.resolve();
    let uploadError: unknown;
    const upload = async (body: Blob, final = false) => {
      await api(`/api/runs/${runId}/evidence?trial_id=${trial.trial_id}&sequence=${sequence}&final=${final}`, { method: 'POST', body });
      sequence++;
    };
    recorder.ondataavailable = event => {
      if (event.data.size) uploads = uploads.then(() => upload(event.data)).catch(e => { uploadError = e; });
    };
    const stopped = new Promise<void>((resolve, reject) => {
      recorder.onstop = () => { uploads.then(async () => {
        if (uploadError) throw uploadError;
        await upload(new Blob(), true); resolve();
      }).catch(reject); };
      recorder.onerror = () => reject(new Error('Ghi video thất bại'));
    });
    // Attach rejection handler immediately; stop() will still report the failure.
    stopped.catch(() => undefined);
    recording.current = { recorder, trialId: trial.trial_id, stop: async () => { if (recorder.state !== 'inactive') recorder.stop(); await stopped; } };
    recorder.start(1000);
    const settings = stream.getVideoTracks()[0].getSettings();
    await api(`/api/runs/${runId}/capture-ready`, { method: 'POST', body: JSON.stringify({ trial_id: trial.trial_id, width: settings.width ?? 1280, height: settings.height ?? 720, frame_rate: settings.frameRate ?? 15, mime_type: mimeType, video_started_monotonic_ms: performance.now(), viewport: viewportRef.current }) });
  }, [stopRecording]);

  useEffect(() => {
    if (!activeRunId) return;
    let disposed = false;
    const events = new EventSource(`/api/runs/${activeRunId}/events`);
    let queue = Promise.resolve();
    events.onmessage = message => {
      const row = JSON.parse(message.data) as Progress;
      setProgress(old => [...old, row].slice(-800));
      if (row.type === 'action' && row.data) setAction(row.data);
      queue = queue.then(async () => {
        if (disposed) return;
        if (row.type === 'capture_requested' && row.trial) await beginRecording(activeRunId, row.trial);
        if (row.type === 'capture_stop') await stopRecording();
        if (['trial_started', 'trial_completed', 'run_finished'].includes(row.type)) await refreshRuns();
        if (row.type === 'run_finished') { events.close(); await stopRecording(); }
      }).catch(async e => {
        setError(String(e));
        await api(`/api/runs/${activeRunId}/cancel`, { method: 'POST', body: JSON.stringify({ reason: 'evidence_lost' }) }).catch(() => undefined);
      });
    };
    const interval = window.setInterval(() => {
      const alive = streamRef.current?.getVideoTracks().some(t => t.readyState === 'live');
      if (alive) api(`/api/runs/${activeRunId}/heartbeat`, { method: 'POST' }).catch(e => setError(String(e)));
      refreshRuns().catch(() => undefined);
    }, 2500);
    return () => { disposed = true; events.close(); window.clearInterval(interval); };
  }, [activeRunId, beginRecording, refreshRuns, stopRecording]);

  useEffect(() => {
    if (videoRef.current && capture && !replay) videoRef.current.srcObject = capture;
  }, [capture, replay]);

  async function connectCapture() {
    setError('');
    try {
      if (!navigator.mediaDevices?.getDisplayMedia) throw new Error('Dùng Chrome/Edge trên localhost để chia sẻ màn hình');
      const stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: { ideal: 15, max: 15 }, width: { max: 1280 }, height: { max: 1280 } }, audio: false });
      if (stream.getVideoTracks()[0].getSettings().displaySurface === 'browser') {
        stream.getTracks().forEach(t => t.stop());
        throw new Error('Hãy chọn cửa sổ Android emulator, không chọn tab trình duyệt');
      }
      streamRef.current?.getTracks().forEach(t => t.stop());
      streamRef.current = stream; setCapture(stream); setReplay(undefined); setViewport(undefined); viewportRef.current = undefined;
      setCalibrating(true); setFirstCorner(undefined);
      stream.getVideoTracks()[0].onended = () => {
        setCapture(undefined); streamRef.current = undefined;
        if (activeRef.current) api(`/api/runs/${activeRef.current}/cancel`, { method: 'POST', body: JSON.stringify({ reason: 'evidence_lost' }) }).catch(() => undefined);
        setError('Chia sẻ màn hình đã dừng. Lượt đang chạy không được coi là hoàn tất.');
      };
    } catch (e) { setError(String(e)); }
  }

  function calibrate(event: React.MouseEvent<HTMLDivElement>) {
    if (!calibrating || !capture || busy) return;
    const video = videoRef.current;
    if (!video) return;
    const rect = video.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) return;
    const point = { x: Math.max(0, Math.min(1, (event.clientX - rect.left)/rect.width)), y: Math.max(0, Math.min(1, (event.clientY - rect.top)/rect.height)) };
    if (!firstCorner) { setFirstCorner(point); return; }
    const box = { left: firstCorner.x, top: firstCorner.y, width: point.x - firstCorner.x, height: point.y - firstCorner.y };
    if (box.width < .1 || box.height < .1) { setFirstCorner(undefined); setError('Chọn góc trên trái rồi góc dưới phải của phần màn hình Android.'); return; }
    setViewport(box); viewportRef.current = box; setCalibrating(false); setFirstCorner(undefined);
  }

  async function start(mode: string) {
    if (!canRun || !currentCase) return;
    setLoading(true); setError(''); setProgress([]); setAction(undefined); setReplay(undefined);
    try {
      const run = await api<Run>('/api/runs', { method: 'POST', body: JSON.stringify({ method_id: selected, case_id: caseId, device_serial: device, network, mode }) });
      setRuns(old => [run, ...old]); setViewRunId(run.id); setActiveRunId(run.id); activeRef.current = run.id;
    } catch (e) { setError(String(e)); }
    finally { setLoading(false); }
  }

  async function openReplay(run: Run, trial: Trial) {
    if (busy) return;
    const rows = await fetch(artifactUrl(run, `${trial.trial_id}/device_live.jsonl`)).then(r => r.text());
    setReplayActions(rows.split('\n').flatMap(line => { try { const value = JSON.parse(line); return value.action ? [value] : []; } catch { return []; } }));
    setAction(undefined); setReplay({ run, trial });
  }

  const groups = [...new Set(methods.map(m => m.group))];
  const actionRows = progress.filter(p => p.type === 'action').slice(-6).reverse();
  const completedTrials = activeRun?.trials.filter(t => t.status === 'completed').length ?? 0;
  const viewportBox = replay?.trial.capture?.viewport ?? viewport;
  const mark = action && viewportBox && action.x != null && action.y != null ? { left: `${100*(viewportBox.left+viewportBox.width*action.x)}%`, top: `${100*(viewportBox.top+viewportBox.height*action.y)}%` } : undefined;

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><div className="brand-symbol"><FlaskConical size={24}/></div><div>adaptive<span>PERFORMANCE LAB</span></div></div>
      <div className="sidebar-caption">KHÔNG GIAN THỰC NGHIỆM</div>
      <label className="search-box"><Search size={17}/><input aria-label="Tìm phương pháp" placeholder="Tìm phương pháp…" value={search} onChange={e => setSearch(e.target.value)}/><kbd>/</kbd></label>
      <nav aria-label="Phương pháp tối ưu">{groups.map(group => {
        const items = methods.filter(m => m.group === group && m.title.toLowerCase().includes(search.toLowerCase()));
        if (!items.length) return null;
        const Icon = icons[group] ?? Activity;
        return <div className="nav-group" key={group}><div className="group-name">{group}</div>{items.map(m => <button key={m.id} aria-current={selected === m.id ? 'page' : undefined} className={`nav-item ${selected === m.id ? 'selected' : ''} ${!m.enabled ? 'muted' : ''}`} onClick={() => { setSelected(m.id); setCaseId(m.cases[0]?.id ?? ''); setViewRunId(''); setSelectedMetric(''); }}><Icon size={17}/><span>{m.title}</span>{!m.enabled ? <span className="soon">Sau</span> : <span className="nav-dot"/>}</button>)}</div>;
      })}</nav>
      <div className="sidebar-bottom"><ShieldCheck size={19}/><div>Dữ liệu đo thực<span>Raw · Trace · Video evidence</span></div><span className="local-label">LOCAL</span></div>
    </aside>

    <div className="workspace">
      <header className="topbar"><div className="breadcrumb">Adaptive News <span>/</span> Phòng đo hiệu năng</div><div className="top-status"><span className={preflight?.devices.length ? 'status good' : 'status'}><Monitor size={15}/>{preflight?.devices.length ? 'Emulator kết nối' : 'Chưa kết nối emulator'}</span><span className={capture ? 'status good' : 'status'}><Radio size={15}/>{capture ? 'Evidence trực tiếp' : 'Evidence chưa bật'}</span><button className="icon-button" title="Kiểm tra môi trường" aria-label="Kiểm tra môi trường" onClick={() => { setInspect(true); check(); }}><Settings2 size={18}/></button></div></header>
      <main>
        <div className="page-heading"><div><div className="eyebrow"><span/> KIỂM CHỨNG TỪNG PHƯƠNG PHÁP</div><h1>{currentMethod?.title ?? 'Performance Lab'}</h1><p>{currentMethod?.description ?? 'Đang kết nối runner local…'}</p></div><button className="button secondary" onClick={() => setInspect(true)}><ListChecks size={17}/> Kiểm tra môi trường</button></div>

        {error && <div role="alert" className="alert error"><CircleAlert size={19}/><span>{error}</span><button aria-label="Đóng thông báo" onClick={() => setError('')}><X size={17}/></button></div>}
        {busy && <div className="active-banner"><LoaderCircle size={18} className="spin"/><span>Đang chạy {methods.find(m => m.id === activeRun?.method_id)?.title ?? ''} · {completedTrials} lượt hoàn tất. Chuyển tab vẫn giữ bài đo.</span><button onClick={() => { if (activeRun) { setSelected(activeRun.method_id); setCaseId(activeRun.case_id); setViewRunId(activeRun.id); } }}>Xem lượt đang chạy</button></div>}

        <div className="main-grid"><section className="results-column">
          <section className="panel setup-panel"><div className="panel-title"><h2><Settings2 size={18}/> Thiết lập đối chứng</h2><span className="tag">CÙNG WORKLOAD</span></div>
            <div className="setup-controls"><label>Kịch bản<div className="select-wrap"><select value={caseId} disabled={busy || !currentMethod?.enabled} onChange={e => { setCaseId(e.target.value); setViewRunId(''); }}>{currentMethod?.cases.map(c => <option key={c.id} value={c.id}>{c.title}</option>)}</select><ChevronDown size={15}/></div></label><label>Điều kiện mạng<div className="select-wrap"><select value={network} disabled={busy} onChange={e => { setNetwork(e.target.value); setViewRunId(''); }}><option value="good">Tốt · 20 Mbps / +40 ms</option><option value="slow">Chậm · 1 Mbps / +250 ms</option></select><ChevronDown size={15}/></div></label></div>
            <div className="comparison"><div><span className="arm-badge">A</span><div><strong>Đối chứng</strong><p>{currentCase ? Object.entries(currentCase.a).map(([k,v]) => configLabel(k, v)).join(' · ') || 'Cùng cấu hình' : 'Chưa triển khai'}</p></div></div><div><span className="arm-badge optimized">B</span><div><strong>Tối ưu</strong><p>{currentCase ? Object.entries(currentCase.b).map(([k,v]) => configLabel(k, v)).join(' · ') || 'Kiểm tra chức năng' : 'Chưa triển khai'}</p></div></div></div>
            <div className="run-controls"><button className="button primary" disabled={!canRun || currentCase?.journey === 'background'} onClick={() => start('demo')}><Play size={17}/> Chạy demo A/B</button><button className="button secondary" disabled={!canRun} onClick={() => start(currentCase?.journey === 'background' ? 'long' : 'report')}><FlaskConical size={17}/>{currentCase?.journey === 'background' ? 'Quan sát dài · 6 giờ' : 'Đo báo cáo · 10 cặp'}</button><button className="button stop" disabled={!busy} onClick={() => api(`/api/runs/${activeRunId}/cancel`, { method: 'POST' }).catch(e => setError(String(e)))}><Square size={14}/> Dừng</button></div>
            <div className="setup-foot"><ShieldCheck size={14}/>{canRun ? 'Sẵn sàng: emulator, dữ liệu và video đã được xác nhận.' : !preflight?.ready ? 'Hoàn tất kiểm tra môi trường trước khi đo.' : !capture ? 'Kết nối evidence để hiển thị emulator đang được thao tác.' : !viewport ? 'Chọn hai góc của màn hình Android trong preview.' : 'Một bài đo đang giữ quyền điều khiển emulator.'}</div>
          </section>

          <div className="section-heading"><h2>Các thông số chính</h2><span>{viewRun ? `${viewRun.mode === 'demo' ? 'Demo · minh họa' : 'Báo cáo'} · ${statusText[viewRun.status] ?? viewRun.status}` : 'Chưa có lượt đo'}</span></div>
          <div className={`metric-grid ${currentMethod && currentMethod.metrics.length <= 2 ? 'two' : ''}`}>{currentMethod?.metrics.map(key => {
            const meta = metrics[key]; const value = viewRun?.summary[key];
            if (!meta) return null;
            return <button key={key} className={`metric-card ${chartMetric === key ? 'focused' : ''}`} onClick={() => setSelectedMetric(key)}><div className="metric-label">{meta.label}<span>{meta.direction === 'lower' ? '↓ tốt hơn' : meta.direction === 'higher' ? '↑ tốt hơn' : 'quan sát'}</span></div><div className="metric-value">{number(value?.B.median, meta.unit)}</div><div className="metric-baseline">A: {number(value?.A.median, meta.unit)}<span>B</span></div><div className={`metric-verdict ${value?.verdict ?? ''}`}>{value?.improvement_pct != null ? `${value.improvement_pct >= 0 ? '+' : ''}${number(value.improvement_pct, '%')} · ` : ''}{verdictText[value?.verdict ?? 'not_measured']}</div><div className="metric-n">n cặp = {value?.pairs ?? 0} · median / IQR</div></button>;
          })}</div>

          <section className="panel chart-panel"><div className="panel-title"><div><h2>So sánh đối chứng & tối ưu</h2><p>{chartMetric ? metrics[chartMetric]?.label : 'Chọn một phương pháp'} · median của các lượt hoàn tất</p></div><div className="legend"><span><i/>Đối chứng</span><span><i/>Tối ưu</span></div></div>
            {chartMetric && metrics[chartMetric] && <div className="chart-wrapper"><Chart metric={metrics[chartMetric]} summary={viewRun?.summary[chartMetric]}/>{!viewRun?.summary[chartMetric]?.pairs && <div className="chart-empty"><div><Activity size={25}/><strong>Chờ dữ liệu thực nghiệm</strong><span>Chạy A/B để xem chênh lệch và độ phân tán.</span></div></div>}</div>}
            <div className="chart-note"><CircleAlert size={15}/><span>Demo n=1 chỉ minh họa. Kết luận khác biệt dùng ≥5 cặp và khoảng tin cậy bootstrap 95%. Không có dữ liệu được giữ là N/A.</span></div>
          </section>

          <section className="panel findings"><div className="panel-title"><h2><FileText size={18}/> Kết quả & giới hạn</h2>{viewRun && <a className="text-link" href={artifactUrl(viewRun, 'report.html')} target="_blank" rel="noreferrer">Mở báo cáo</a>}</div><p>{currentMethod?.limitation || 'Chỉ thay đổi kỹ thuật được chọn; giữ cùng dữ liệu và chuỗi thao tác ở cả hai nhánh.'}</p>{currentCase?.note && <p>{currentCase.note}</p>}<div className="fact-row"><span><Monitor size={15}/> Android emulator</span><span><Video size={15}/> Video ghi trên host</span><span><Cpu size={15}/> Pin / năng lượng: N/A</span></div>{viewRun?.error && <div className="run-error">{viewRun.error}</div>}{viewRun && terminal(viewRun.status) && <div className="export-links"><a href={artifactUrl(viewRun, 'run_metrics.csv')}><ArrowDownToLine size={15}/> CSV số đo</a><a href={artifactUrl(viewRun, 'evidence.zip')}><ArrowDownToLine size={15}/> Gói evidence</a><a href={artifactUrl(viewRun, 'manifest.json')} target="_blank" rel="noreferrer"><ShieldCheck size={15}/> SHA-256 manifest</a></div>}</section>

          <section className="panel history"><div className="panel-title"><h2><Clock3 size={18}/> Lịch sử thực nghiệm</h2><span>{runs.filter(r => r.method_id === selected).length} phiên</span></div>{runs.filter(r => r.method_id === selected).length ? <div className="table-scroll"><table><thead><tr><th>Phiên / lượt</th><th>Điều kiện</th><th>Trạng thái</th><th>Evidence</th></tr></thead><tbody>{runs.filter(r => r.method_id === selected).slice(0, 8).map(run => <React.Fragment key={run.id}><tr className="run-row"><td><button disabled={busy} onClick={() => { setViewRunId(run.id); setCaseId(run.case_id); setNetwork(run.network); }}>{new Date(run.created_at).toLocaleString('vi-VN', { timeZone: 'Asia/Saigon', hour12: false })}</button><small>{run.id}</small></td><td>{run.mode} · {run.network}</td><td><span className={`run-status ${run.status}`}>{statusText[run.status]}</span></td><td>{run.trials.filter(t => t.status === 'completed').length}/{run.trials.length} lượt</td></tr>{(run.id === viewRun?.id) && run.trials.map(t => <tr key={t.trial_id} className="trial-row"><td><span className={`arm-badge ${t.arm === 'B' ? 'optimized' : ''}`}>{t.arm}</span> Lặp {t.repeat} · {t.phase === 'memory' ? 'RAM riêng' : 'Hiệu năng'}</td><td><code>{t.config_hash.slice(0, 8)}</code></td><td>{statusText[t.status]}</td><td><button className="text-link" disabled={!t.video_finalized || busy} onClick={() => openReplay(run, t).catch(e => setError(String(e)))}>Xem video</button></td></tr>)}</React.Fragment>)}</tbody></table></div> : <div className="history-empty"><Clock3 size={22}/><div>Chưa có phiên thực nghiệm<span>Các lượt đo và bằng chứng sẽ được giữ lại tại đây.</span></div></div>}</section>
        </section>

        <aside className="evidence-column"><section className="panel evidence-panel"><div className="panel-title"><h2><Monitor size={18}/> {replay ? 'Video evidence' : 'Emulator trực tiếp'}</h2><span className={`live-badge ${capture && !replay ? 'on' : ''}`}>{replay ? 'PHÁT LẠI' : capture ? 'LIVE' : 'CHƯA KẾT NỐI'}</span></div>
          <label className="device-select"><Monitor size={16}/><select aria-label="Chọn emulator" value={device} disabled={busy} onChange={e => setDevice(e.target.value)}>{!preflight?.devices.length && <option value="">Chưa có emulator</option>}{preflight?.devices.map(d => <option key={d.serial} value={d.serial}>{d.avd} · API {d.api_level}</option>)}</select></label>
          <div className={`device-stage ${capture || replay ? 'has-video' : ''} ${calibrating ? 'calibrating' : ''}`} onClick={calibrate}>
            {capture || replay ? <div className="video-surface" style={{ maxWidth: 620 * videoRatio }}><video key={replay ? replay.trial.trial_id : 'live'} ref={videoRef} onLoadedMetadata={event => { const v = event.currentTarget; if (v.videoWidth && v.videoHeight) setVideoRatio(v.videoWidth / v.videoHeight); }} autoPlay={!replay} muted playsInline controls={Boolean(replay)} src={replay ? artifactUrl(replay.run, `${replay.trial.trial_id}/evidence.webm`) : undefined} onTimeUpdate={e => { if (replay) { const t = e.currentTarget.currentTime + (replay.trial.capture?.host_started_monotonic ?? 0); setAction(replayActions.filter(a => (a.host_received_monotonic ?? Infinity) <= t).at(-1)); } }}/>{viewportBox && <div className="android-viewport" style={{ left: `${viewportBox.left*100}%`, top: `${viewportBox.top*100}%`, width: `${viewportBox.width*100}%`, height: `${viewportBox.height*100}%` }}/>}{mark && action?.x2 != null && action?.y2 != null && viewportBox && <svg className="gesture-overlay" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><line x1={100*(viewportBox.left+viewportBox.width*(action.x ?? 0))} y1={100*(viewportBox.top+viewportBox.height*(action.y ?? 0))} x2={100*(viewportBox.left+viewportBox.width*action.x2)} y2={100*(viewportBox.top+viewportBox.height*action.y2)} stroke="#ffcd49" strokeWidth="0.7" strokeLinecap="round"/></svg>}{mark && <div className="touch-marker" style={mark}><MousePointer2 size={18}/></div>}{firstCorner && <span className="corner-marker" style={{ left: `${firstCorner.x*100}%`, top: `${firstCorner.y*100}%` }}/>}</div> : <div className="device-placeholder"><div className="phone-outline"><div className="phone-speaker"/><FlaskConical size={36}/><span>Adaptive News</span><div className="phone-home"/></div><strong>Màn hình thật. Thao tác thật.</strong><p>Kết nối cửa sổ emulator để theo dõi kiểm thử và ghi video bằng chứng.</p></div>}
          </div>
          {calibrating && <div className="calibration-tip">{firstCorner ? 'Bước 2: bấm góc dưới phải màn hình Android.' : 'Bước 1: bấm góc trên trái màn hình Android.'} Không tính thanh tiêu đề hoặc toolbar emulator.</div>}
          <div className="capture-buttons"><button className="button secondary" disabled={busy} onClick={connectCapture}><Video size={16}/>{capture ? 'Đổi cửa sổ' : 'Kết nối evidence'}</button>{replay ? <button className="text-link" onClick={() => { setReplay(undefined); setAction(undefined); }}>Về trực tiếp</button> : capture && <button className="text-link" disabled={busy} onClick={() => { setCalibrating(true); setFirstCorner(undefined); }}>Căn vùng Android</button>}</div>
          <div className="current-action"><span className="action-icon"><MousePointer2 size={18}/></span><div><strong>{action?.action ?? 'Chờ kịch bản bắt đầu'}</strong><span>{action ? ({ sent: 'Đã gửi thao tác đến Android', completed: 'Đã xác nhận hoàn tất', failed: 'Thao tác thất bại' }[action.state] ?? action.state) : 'UIAutomator sẽ điều khiển app khi bấm chạy.'}</span></div>{action?.state === 'completed' ? <CheckCircle2 size={18} className="green"/> : busy ? <LoaderCircle size={18} className="spin"/> : <Circle size={16}/>}</div>
          <div className="evidence-proof"><ShieldCheck size={14}/><span>Video gốc + log theo lượt. Dấu thao tác được dựng từ lệnh đã gửi; không thay nội dung màn hình gốc.</span></div>
        </section>
        <section className="panel action-panel"><div className="panel-title"><h2><ListChecks size={18}/> Nhật ký thao tác</h2><span className="tag">THỜI GIAN THỰC</span></div>{actionRows.length ? <div className="action-list">{actionRows.map(row => <div className="action-entry" key={row.id}><span className={`action-state ${row.data?.state}`}>{row.data?.state === 'completed' ? <Check size={13}/> : <MousePointer2 size={13}/>}</span><div><strong>{row.data?.action}</strong><span>{row.data?.state === 'completed' ? 'Hoàn tất' : row.data?.state === 'failed' ? 'Thất bại' : 'Đã gửi'} · {row.trial_id?.split('_').slice(-2).join(' · ')}</span></div><time>{row.host_monotonic.toFixed(1)}s</time></div>)}</div> : <div className="log-placeholder"><ListChecks size={23}/><p>Thao tác chạm, nhập và cuộn sẽ xuất hiện khi kiểm thử thực sự chạy.</p></div>}</section>
        <div className="protocol-card"><div><ShieldCheck size={18}/><strong>Để kết quả có thể kiểm chứng</strong></div><p>Cùng dataset, cùng thao tác, một yếu tố thay đổi. RAM thu ở lượt riêng. Giữ cả kết quả suy giảm và lượt thất bại.</p><span>Không có số đo mô phỏng trên dashboard.</span></div>
        </aside></div>
        <footer><span>ADAPTIVE NEWS · NHÓM G3</span><span>Context-aware performance optimization</span><span>Local execution · Evidence first</span></footer>
      </main>
    </div>

    {inspect && <div className="modal-backdrop" onClick={() => setInspect(false)}><section className="modal" role="dialog" aria-modal="true" aria-labelledby="preflight-title" onClick={e => e.stopPropagation()}><div className="panel-title"><h2 id="preflight-title"><ListChecks size={20}/> Kiểm tra môi trường</h2><button className="icon-button" aria-label="Đóng" onClick={() => setInspect(false)}><X size={20}/></button></div><p>Không khởi chạy bài đo khi thiếu công cụ hoặc dữ liệu.</p><div className="check-list">{preflight?.checks.map(c => <div key={c.id}>{c.ok ? <CheckCircle2 size={21} className="green"/> : <CircleAlert size={21} className="amber"/>}<div><strong>{c.title}</strong><span>{c.detail}</span></div><span className={c.ok ? 'check-ok' : 'check-missing'}>{c.ok ? 'Đạt' : 'Cần thiết lập'}</span></div>)}</div><div className="modal-footer"><span>SDK / JDK được cấu hình qua ANDROID_HOME, JAVA_HOME và LAB_ADB.</span><button className="button primary" onClick={check} disabled={checking}><RefreshCw size={16} className={checking ? 'spin' : ''}/>{checking ? 'Đang kiểm tra' : 'Kiểm tra lại'}</button></div></section></div>}
  </div>;
}

createRoot(document.getElementById('root')!).render(<App/>);
