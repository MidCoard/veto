import ReactMarkdown, {type Components} from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Box, Divider, Link, Typography} from '@mui/material';

const components:Partial<Components>={
 h1:({children})=><Typography component="h1" variant="h6" sx={{mt:2,mb:1}}>{children}</Typography>,
 h2:({children})=><Typography component="h2" variant="subtitle1" sx={{mt:2,mb:1}}>{children}</Typography>,
 h3:({children})=><Typography component="h3" variant="subtitle2" sx={{mt:1.5,mb:0.75}}>{children}</Typography>,
 h4:({children})=><Typography component="h4" variant="subtitle2" sx={{mt:1.5,mb:0.75}}>{children}</Typography>,
 p:({children})=><Typography component="p" sx={{my:0.75,lineHeight:1.65}}>{children}</Typography>,
 ul:({children})=><Box component="ul" sx={{my:0.75,pl:3,listStyle:'disc'}}>{children}</Box>,
 ol:({children})=><Box component="ol" sx={{my:0.75,pl:3,listStyle:'decimal'}}>{children}</Box>,
 li:({children})=><Box component="li" sx={{my:0.25,'&>p':{my:0}}}>{children}</Box>,
 blockquote:({children})=><Box component="blockquote" sx={{my:1,mx:0,pl:1.5,borderLeft:2,borderColor:'primary.main',color:'text.secondary'}}>{children}</Box>,
 a:({href,children})=><Link href={href} target="_blank" rel="noopener noreferrer" underline="hover">{children}</Link>,
 img:({alt})=><Typography component="span" color="text.secondary">[图片{alt?`：${alt}`:''}]</Typography>,
 code:({className,children})=><Box component="code" className={className} sx={{px:0.4,borderRadius:0.5,bgcolor:'background.default',fontFamily:'monospace',fontSize:12}}>{children}</Box>,
 pre:({children})=><Box component="pre" sx={{my:1,p:1.5,border:1,borderColor:'divider',borderRadius:1,bgcolor:'background.default',whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:12,lineHeight:1.6,'& code':{p:0,bgcolor:'transparent'}}}>{children}</Box>,
 table:({children})=><Box sx={{my:1,maxWidth:'100%',overflowX:'auto'}}><Box component="table" sx={{borderCollapse:'collapse',width:'100%',fontSize:12}}>{children}</Box></Box>,
 th:({children})=><Box component="th" sx={{border:1,borderColor:'divider',bgcolor:'background.default',p:1,textAlign:'left',fontWeight:600}}>{children}</Box>,
 td:({children})=><Box component="td" sx={{border:1,borderColor:'divider',p:1}}>{children}</Box>,
 hr:()=><Divider sx={{my:2}}/>,
};

export default function MarkdownText({content}:{content:string}) {
 return <Box sx={{minWidth:0,overflowWrap:'anywhere','& > :first-of-type':{mt:0},'& > :last-child':{mb:0}}}><ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{content}</ReactMarkdown></Box>;
}
