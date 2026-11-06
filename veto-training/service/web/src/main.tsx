import React from 'react';
import { createRoot } from 'react-dom/client';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import App from './App';

const theme = createTheme({
 palette:{mode:'dark',primary:{main:'#38BDF8'},error:{main:'#E5484D'},background:{default:'#0E1116',paper:'#161A21'},text:{primary:'#E3E7EE',secondary:'#8B94A3'},divider:'#2B323E'},
 shape:{borderRadius:6},
 typography:{fontFamily:'"IBM Plex Sans", "Segoe UI", "Microsoft YaHei", system-ui, sans-serif',fontSize:13,button:{textTransform:'none',fontWeight:500}},
 components:{
  MuiCssBaseline:{styleOverrides:{
   html:{colorScheme:'dark',scrollbarWidth:'thin',scrollbarColor:'#2B323E #0E1116'},
   '*':{scrollbarWidth:'thin',scrollbarColor:'#2B323E #0E1116'},
   '*::-webkit-scrollbar':{width:8,height:8},
   '*::-webkit-scrollbar-track':{background:'#0E1116'},
   '*::-webkit-scrollbar-thumb':{background:'#2B323E',borderRadius:4},
   '*::-webkit-scrollbar-thumb:hover':{background:'#8B94A3'},
   '::selection':{background:'rgba(56,189,248,.25)',color:'#E3E7EE'},
  }},
  MuiButtonBase:{defaultProps:{disableRipple:true},styleOverrides:{root:{'&.Mui-focusVisible':{outline:'2px solid #8B94A3',outlineOffset:2}}}},
  MuiButton:{defaultProps:{disableElevation:true,size:'small'},styleOverrides:{root:{borderRadius:6,minHeight:30},text:{color:'#C9D0DA'},outlined:{borderColor:'#2B323E'},contained:{color:'#0B1015'}}},
  MuiTextField:{defaultProps:{size:'small'}},
  MuiInputBase:{styleOverrides:{root:{fontSize:13},input:{padding:'8px 10px'}}},
  MuiOutlinedInput:{styleOverrides:{root:{minHeight:36,backgroundColor:'#0E1116',fieldset:{borderColor:'#2B323E'},'&:hover fieldset':{borderColor:'#8B94A3'},'&.Mui-focused fieldset':{borderColor:'#8B94A3'}}}},
  MuiSelect:{styleOverrides:{icon:{color:'#8B94A3'}}},
  MuiMenu:{styleOverrides:{paper:{backgroundColor:'#1E242E',border:'1px solid #2B323E'}}},
  MuiTab:{styleOverrides:{root:{minHeight:34,minWidth:72,padding:'6px 12px',borderRadius:6,textTransform:'none',alignItems:'flex-start','&.Mui-selected':{backgroundColor:'#1E242E',color:'#38BDF8'}}}},
  MuiTabs:{styleOverrides:{root:{minHeight:34},indicator:{display:'none'}}},
  MuiChip:{styleOverrides:{root:{borderRadius:4,height:22,fontSize:11}}},
  MuiPaper:{styleOverrides:{root:{backgroundImage:'none',boxShadow:'none'}}},
  MuiAccordion:{styleOverrides:{root:{backgroundColor:'transparent'}}},
  MuiAccordionSummary:{styleOverrides:{root:{minHeight:32,padding:0,'&.Mui-expanded':{minHeight:32}},content:{margin:0,'&.Mui-expanded':{margin:0}}}},
  MuiDialog:{styleOverrides:{paper:{border:'1px solid #2B323E',borderRadius:10,backgroundColor:'#161A21'}}},
  MuiDialogContent:{styleOverrides:{root:{padding:'12px 20px'}}},
  MuiDialogActions:{styleOverrides:{root:{padding:'8px 20px 16px'}}},
  MuiTableCell:{styleOverrides:{root:{padding:'8px 12px',borderColor:'#2B323E'}}},
  MuiListItemButton:{styleOverrides:{root:{padding:'8px 12px','&.Mui-selected':{backgroundColor:'#1E242E'}}}},
  MuiAlert:{styleOverrides:{root:{fontSize:12}}},
  MuiLinearProgress:{styleOverrides:{root:{height:3}}},
 }
});
const cache = createCache({key:'veto',nonce:document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content});
createRoot(document.getElementById('root')!).render(<React.StrictMode><CacheProvider value={cache}><ThemeProvider theme={theme}><CssBaseline/><App/></ThemeProvider></CacheProvider></React.StrictMode>);
