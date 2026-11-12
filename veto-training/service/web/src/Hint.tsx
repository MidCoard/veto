import { Tooltip as MuiTooltip, type TooltipProps } from '@mui/material';

export default function Tooltip(props:TooltipProps) {
 return <MuiTooltip {...props} describeChild/>;
}
