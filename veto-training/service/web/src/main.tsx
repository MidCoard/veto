import React, { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import App from './App';

function trainerTheme(mode:'light'|'dark') {
 const dark=mode==='dark';
 const colors=dark?{ink:'#0E1116',panel:'#161A21',raised:'#1E242E',rule:'#2B323E',text:'#E3E7EE',dim:'#8B94A3',accent:'#38BDF8',onAccent:'#0B1015'}:{ink:'#F3F5F8',panel:'#FFFFFF',raised:'#E9EDF3',rule:'#D6DDE6',text:'#1B2230',dim:'#5B6675',accent:'#0369A1',onAccent:'#FFFFFF'};
 return createTheme({
  palette:{mode,primary:{main:colors.accent},error:{main:'#E5484D'},background:{default:colors.ink,paper:colors.panel},text:{primary:colors.text,secondary:colors.dim},divider:colors.rule},
  shape:{borderRadius:6},
  typography:{fontFamily:'"IBM Plex Sans", "Segoe UI", "Microsoft YaHei", system-ui, sans-serif',fontSize:13,button:{textTransform:'none',fontWeight:500}},
  components:{
   MuiCssBaseline:{styleOverrides:{
    html:{colorScheme:mode,scrollbarWidth:'thin',scrollbarColor:`${colors.rule} ${colors.ink}`},
    '*':{scrollbarWidth:'thin',scrollbarColor:`${colors.rule} ${colors.ink}`},
    '*::-webkit-scrollbar':{width:8,height:8},
    '*::-webkit-scrollbar-track':{background:colors.ink},
    '*::-webkit-scrollbar-thumb':{background:colors.rule,borderRadius:4},
    '*::-webkit-scrollbar-thumb:hover':{background:colors.dim},
    '::selection':{background:dark?'rgba(56,189,248,.25)':'rgba(3,105,161,.18)',color:colors.text},
   }},
   MuiButtonBase:{defaultProps:{disableRipple:true},styleOverrides:{root:{'&.Mui-focusVisible':{outline:`2px solid ${colors.accent}`,outlineOffset:2,boxShadow:dark?'0 0 0 4px rgba(56,189,248,.12)':'0 0 0 4px rgba(3,105,161,.12)'}}}},
   MuiButton:{defaultProps:{disableElevation:true,size:'small'},styleOverrides:{root:{borderRadius:6,minWidth:0,minHeight:30,transition:'background-color 150ms ease,border-color 150ms ease,color 150ms ease,box-shadow 150ms ease','&.MuiButton-colorError':{color:'#E5484D','&:hover':{backgroundColor:'rgba(229,72,77,.10)'}}},text:{color:colors.text,'&:hover':{backgroundColor:dark?'rgba(56,189,248,.09)':'rgba(3,105,161,.08)'}},outlined:{borderColor:dark?'rgba(56,189,248,.35)':'rgba(3,105,161,.35)',backgroundColor:dark?'rgba(56,189,248,.04)':'rgba(3,105,161,.04)','&:hover':{borderColor:colors.accent,backgroundColor:dark?'rgba(56,189,248,.12)':'rgba(3,105,161,.12)'}},contained:{color:colors.onAccent,boxShadow:dark?'0 2px 6px rgba(56,189,248,.16)':'0 2px 6px rgba(3,105,161,.16)','&:hover':{backgroundColor:dark?'#6CCDFA':'#025B8A',boxShadow:dark?'0 3px 10px rgba(56,189,248,.22)':'0 3px 10px rgba(3,105,161,.22)'}}}},
   MuiTextField:{defaultProps:{size:'small'}},
   MuiInputBase:{styleOverrides:{root:{fontSize:13},input:{padding:'8px 10px'}}},
   MuiOutlinedInput:{styleOverrides:{root:{minHeight:36,backgroundColor:colors.ink,fieldset:{borderColor:colors.rule},'&:hover fieldset':{borderColor:colors.dim},'&.Mui-focused fieldset':{borderColor:colors.dim}}}},
   MuiSelect:{styleOverrides:{icon:{color:colors.dim}}},
   MuiMenu:{styleOverrides:{paper:{backgroundColor:colors.raised,border:`1px solid ${colors.rule}`,borderRadius:6,marginTop:4,boxShadow:'0 8px 24px rgba(0,0,0,.18)'}}},
   MuiMenuList:{styleOverrides:{root:{padding:4}}},
   MuiMenuItem:{styleOverrides:{root:{minHeight:30,padding:'4px 8px',borderRadius:4,fontSize:12,'&:hover':{backgroundColor:dark?'rgba(227,231,238,.06)':'rgba(27,34,48,.06)'},'&.Mui-selected':{backgroundColor:dark?'rgba(56,189,248,.12)':'rgba(3,105,161,.12)',color:colors.accent},'&.Mui-selected:hover,&.Mui-focusVisible':{backgroundColor:dark?'rgba(56,189,248,.18)':'rgba(3,105,161,.18)'}}}},
   MuiTab:{styleOverrides:{root:{position:'relative',minHeight:34,minWidth:72,padding:'6px 12px',borderRadius:6,textTransform:'none',alignItems:'flex-start',transition:'background-color 150ms ease,color 150ms ease','&:hover':{backgroundColor:dark?'rgba(56,189,248,.08)':'rgba(3,105,161,.07)',color:colors.accent},'&.Mui-selected':{backgroundColor:dark?'rgba(56,189,248,.12)':'rgba(3,105,161,.10)',color:colors.accent},'&.Mui-selected::before':{content:'""',position:'absolute',left:0,top:8,bottom:8,width:2,borderRadius:2,backgroundColor:colors.accent}}}},
   MuiTabs:{styleOverrides:{root:{minHeight:34},indicator:{display:'none'}}},
   MuiChip:{styleOverrides:{root:{borderRadius:5,height:24,fontSize:11.5,fontWeight:550},label:{display:'inline-flex',alignItems:'center',justifyContent:'center',padding:'0 8px',lineHeight:1}}},
   MuiDialogTitle:{styleOverrides:{root:{padding:'18px 20px 12px',fontSize:17,fontWeight:650,letterSpacing:-0.2}}},
   MuiPaper:{styleOverrides:{root:{backgroundImage:'none',boxShadow:'none'}}},
   MuiAccordion:{styleOverrides:{root:{backgroundColor:'transparent'}}},
   MuiAccordionSummary:{styleOverrides:{root:{minHeight:32,padding:0,borderRadius:4,transition:'background-color 150ms ease','&:hover':{backgroundColor:dark?'rgba(56,189,248,.06)':'rgba(3,105,161,.05)'},'&.Mui-expanded':{minHeight:32}},content:{margin:0,minWidth:0,overflow:'hidden','&.Mui-expanded':{margin:0}}}},
   MuiDialog:{styleOverrides:{paper:{border:`1px solid ${colors.rule}`,borderRadius:10,backgroundColor:colors.panel,boxShadow:dark?'0 16px 48px rgba(0,0,0,.32)':'0 16px 48px rgba(27,34,48,.14)'}}},
   MuiDialogContent:{styleOverrides:{root:{padding:'12px 20px'}}},
   MuiDialogActions:{styleOverrides:{root:{padding:'8px 20px 16px'}}},
   MuiTableCell:{styleOverrides:{root:{padding:'8px 12px',borderColor:colors.rule}}},
   MuiTableRow:{styleOverrides:{root:{transition:'background-color 150ms ease','&:hover':{backgroundColor:dark?'rgba(56,189,248,.035)':'rgba(3,105,161,.035)'}}}},
   MuiListItemButton:{styleOverrides:{root:{padding:'8px 12px',transition:'background-color 150ms ease','&:hover':{backgroundColor:dark?'rgba(56,189,248,.08)':'rgba(3,105,161,.07)'},'&.Mui-selected':{backgroundColor:dark?'rgba(56,189,248,.12)':'rgba(3,105,161,.10)'}}}},
   MuiAlert:{styleOverrides:{root:{fontSize:12}}},
   MuiLinearProgress:{styleOverrides:{root:{height:3}}},
   MuiTooltip:{defaultProps:{arrow:true,enterDelay:300}},
  }
 });
}

function Root() {
 const [mode,setMode]=useState<'light'|'dark'>(()=>{try{return localStorage.getItem('veto-trainer-theme')==='dark'?'dark':'light';}catch{return 'light';}});
 const theme=useMemo(()=>trainerTheme(mode),[mode]);
 const toggle=()=>{const next=mode==='dark'?'light':'dark';setMode(next);try{localStorage.setItem('veto-trainer-theme',next);}catch{/* Theme choice remains valid for this session. */}};
 return <React.StrictMode><CacheProvider value={cache}><ThemeProvider theme={theme}><CssBaseline/><App themeMode={mode} onToggleTheme={toggle}/></ThemeProvider></CacheProvider></React.StrictMode>;
}
const cache = createCache({key:'veto',nonce:document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content});
createRoot(document.getElementById('root')!).render(<Root/>);
