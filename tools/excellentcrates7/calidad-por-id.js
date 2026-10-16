// Asigna calidad a cada identifier de premio: emparejando calidades.json contra las .bak del dev (misma logica que generador.js)
const fs=require('fs'),path=require('path');
const spec=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));const dir=process.argv[3];
const out={};
for(const [idCaja,caja] of Object.entries(spec.cajas)){
  const f=path.join(dir,idCaja+'.yml.bak-calidades-20261006');
  if(!fs.existsSync(f)){out[idCaja]={_error:'sin bak'};continue}
  const L=fs.readFileSync(f,'utf8').split(/\r?\n/);
  const iRew=L.findIndex(l=>/^rewards:\s*$/.test(l));let fin=iRew+1;while(fin<L.length&&(L[fin]===''||/^\s/.test(L[fin])))fin++;
  const bl=[];for(let i=iRew+1;i<fin;i++){const m=L[i].match(/^  '?(\d+)'?:\s*$/);if(m)bl.push({n:+m[1],lineas:[]});if(bl.length)bl[bl.length-1].lineas.push(L[i]);}
  out[idCaja]={};
  for(const b of bl){
    const cand=caja.premios.filter(p=>!p.nuevo&&(p.n!==undefined?p.n===b.n:b.lineas.some(l=>{const t=l.trim().replace(/^- /,'');return p.match.startsWith('material: ')?t===p.match:t.includes(p.match)})));
    const id=(b.lineas.find(l=>/^    identifier:/.test(l))||'').replace(/^\s+identifier:\s*/,'');
    out[idCaja][id]=cand.length===1?(cand[0].calidad||'apagado'):('?'+cand.length);
  }
  for(const p of caja.premios.filter(p=>p.nuevo)) out[idCaja][p.nuevo.identifier]=p.calidad||'apagado';
}
fs.writeFileSync(process.argv[4],JSON.stringify(out,null,1));
for(const [c,v] of Object.entries(out)){const k={};for(const q of Object.values(v))k[q]=(k[q]||0)+1;console.log(c,JSON.stringify(k))}
